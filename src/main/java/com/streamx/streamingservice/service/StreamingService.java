package com.streamx.streamingservice.service;

import com.streamx.streamingservice.dto.StreamingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.presigned-url-expiry}")
    private long presignedUrlExpiryInMinutes;

    @Value("${streaming.base-url}")
    private String streamingBaseUrl;

    private static final int PLAYLIST_CACHE_SAFETY_MINUTES = 5;

    // Redis key containing the master playlist key for a movie
    private final static String MASTER_PLAYLIST_KEY_PREFIX = "streaming:playlist:";

    // Redis key prefix for rewritten playlists
    private final static String REWRITTEN_PLAYLIST_CACHE_PREFIX = "streaming:rewritten:";

    /**
     * Generate a playlist URL for the given movie.
     * The returned URL is not a direct s3 URL.
     *
     * FLOW:
     * 1. Check if the presigned URL is cached in Redis.
     * 2. If cached, return the cached URL.
     * 3. If not cached, generate a new presigned URL using S3Presigner.
     * 4. Cache the new presigned URL in Redis with an expiration time.
     * 5. Return the presigned URL.
     */
    public StreamingResponse getStreamingUrl(String movieId) {

        log.info("Generating streaming URL for movieId: {}", movieId);

        String masterPlaylistKey = "encoded/" + movieId + "/master.m3u8";

        //The player will request this URL
        String playlistUrl = buildPlaylistUrl(movieId, masterPlaylistKey);

        log.info("Streaming URL Generated and cached for movieId: {}", movieId);

        return StreamingResponse.builder()
                .movieId(movieId)
                .streamingUrl(playlistUrl)
                .quality("1080p, 720p, 480p")
                .expiresInMinutes(presignedUrlExpiryInMinutes)
                .build();
    }

    /**
     * Builds a URL pointing to our Spring playlist endpoint.
     */
    private String buildPlaylistUrl(String movieId, String playlistPath) {
        return UriComponentsBuilder
                .fromUriString(streamingBaseUrl)
                .path(
                        "/api/v1/stream/{movieId}/playlist"
                )
                .queryParam("path", playlistPath)
                .buildAndExpand(movieId)
                .encode()
                .toUriString();
    }

    /**
     * Read m3u8 file from S3 and rewrite all
     * segment URLs with presigned URLs.
     *
     * This is the KEY method that makes everything secure.
     *
     * Original m3u8:
     * #EXTM3U
     * 1080p/playlist.m3u8
     * 720p/playlist.m3u8
     *
     * Rewritten m3u8:
     * #EXTM3U
     * https://s3.../1080p/playlist.m3u8?X-Amz-Signature=...
     * https://s3.../720p/playlist.m3u8?X-Amz-Signature=...
     */
    public String getSignedPlaylist(String movieId, String playlistPath) {
        log.info("Getting signed playlist for movie: {} path: {}",
                movieId, playlistPath);

        /*
         * Security check:
         * A movie should only be allowed to access
         * objects under its own encoded directory.
         */
        String expectedPrefix = "encoded/" + movieId + "/";

        if (!playlistPath.startsWith(expectedPrefix)) {

            log.warn("Invalid playlist path {} for movie {}", playlistPath, movieId);

            throw new IllegalArgumentException("Invalid playlist path");
        }

        /*
         * We cannot cache only by movieId because one movie has multiple playlists:
         * master.m3u8
         * 1080p/playlist.m3u8
         * 720p/playlist.m3u8
         * 480p/playlist.m3u8
         */
        String cacheKey = REWRITTEN_PLAYLIST_CACHE_PREFIX + movieId + ":" + playlistPath;

        /*
         * Check Redis first for cached rewritten playlist
         */
        String cachedPlaylist = redisTemplate.opsForValue().get(cacheKey);

        if (cachedPlaylist != null) {

            log.debug("Rewritten playlist found in Redis: {}", playlistPath);

            return cachedPlaylist;
        }

        // Get base path for this playlist
        String basePath = playlistPath.substring(0,
                playlistPath.lastIndexOf("/") + 1);

        // Read m3u8 content from S3
        String m3u8Content = readFromS3(playlistPath);

        // Rewrite each line that is a segment or playlist reference
        String rewrittenPlaylist = rewriteM3u8WithSignedUrls(m3u8Content, basePath, movieId);

        long cacheMinutes = presignedUrlExpiryInMinutes - PLAYLIST_CACHE_SAFETY_MINUTES;

        redisTemplate.opsForValue().set(
                cacheKey,
                rewrittenPlaylist,
                Duration.ofMinutes(cacheMinutes)
        );

        log.info("Rewritten playlist generated and cached: {}", playlistPath);

        return rewrittenPlaylist;
    }

    /**
     * Rewrite m3u8 content replacing relative paths
     * with presigned S3 URLs.
     */
    private String rewriteM3u8WithSignedUrls(
            String m3u8Content, String basePath, String movieId) {

        StringBuilder rewritten = new StringBuilder();

        for (String line : m3u8Content.split("\\R", -1)) {
            String trimmed = line.trim();

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                rewritten.append(line).append("\n");
                continue;
            }

            /* This is a segment or playlist reference

            Example:
             * 1080p/playlist.m3u8
             * or:
             * segment_001.ts
             * Build full S3 key and sign it
             */
            String fullKey = basePath + trimmed;

            /*
             * Child playlist: 1080p/playlist.m3u8 or 720p/playlist.m3u8
             * Send the request back through our application.
             */
            if (trimmed.endsWith(".m3u8")) {

                String playlistUrl = buildPlaylistUrl(movieId, fullKey);

                rewritten.append(playlistUrl).append("\n");
            }
            else {
                /*
                 * Media segment: Signed url for .ts segments file
                 * Give the player a temporary S3 URL.
                 */
                String signedUrl = generatePresignedUrl(fullKey);

                rewritten.append(signedUrl).append("\n");
            }
        }

        return rewritten.toString();
    }

    /**
     * Read file content from S3.
     */
    private String readFromS3(String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        try(
                ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);

                BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response))
        ) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
        catch (Exception e) {

            log.error("Failed to read playlist from S3: {}", s3Key, e);

            throw new RuntimeException("Failed to read playlist from S3", e);
        }
    }

    /**
     * Generates a temporary S3 GET URL for a media segment.
     *
     * Example:
     * encoded/movie123/1080p/segment_001.ts
     *      ↓
     * https://bucket.s3.amazonaws.com/...?     * ?X-Amz-Signature=...*/
    private String generatePresignedUrl(String playlistKey) {

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(playlistKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(presignedUrlExpiryInMinutes))
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    public void invalidateCachedUrl(String movieId) {
        String[] playlistPaths = {
                "encoded/" + movieId + "/master.m3u8",
                "encoded/" + movieId + "/1080p/playlist.m3u8",
                "encoded/" + movieId + "/720p/playlist.m3u8",
                "encoded/" + movieId + "/480p/playlist.m3u8"
        };

        for (String playlistPath : playlistPaths) {

            String cacheKey =
                    REWRITTEN_PLAYLIST_CACHE_PREFIX
                            + movieId
                            + ":"
                            + playlistPath;

            redisTemplate.delete(cacheKey);
        }
        log.info("Invalidated rewritten playlist cache for movieId: {}", movieId);
    }
}
