package com.streamx.streamingservice.service;

import com.streamx.streamingservice.dto.StreamingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingService {

    private final S3Presigner s3Presigner;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.presigned-url-expiry}")
    private long presignedUrlExpiryInMinutes;

    private final static String STREAMING_URL_CACHE_PREFIX = "streaming:url:";

    /**
     * Generate a presigned streaming URL for the given movie.
     *
     * FLOW:
     * 1. Check if the presigned URL is cached in Redis.
     * 2. If cached, return the cached URL.
     * 3. If not cached, generate a new presigned URL using S3Presigner.
     * 4. Cache the new presigned URL in Redis with an expiration time.
     * 5. Return the presigned URL.
     */
    public StreamingResponse getStreamingUrl(String movieId, String playlistKey) {

        log.info("Generating streaming URL for movieId: {}", movieId);

        String cacheKey = STREAMING_URL_CACHE_PREFIX + movieId;

        // Check if the presigned URL is cached in Redis
        String cachedUrl = redisTemplate.opsForValue().get(cacheKey);
        if (cachedUrl != null) {
            log.info("Found cached streaming URL for movieId: {}", movieId);
            return StreamingResponse.builder()
                    .movieId(movieId)
                    .streamingUrl(cachedUrl)
                    .quality("1080p, 720p, 480p") // Assuming quality is HD for cached URLs
                    .expiresInMinutes(presignedUrlExpiryInMinutes)
                    .build();
        }

        // If not cached, generate a new presigned URL using S3Presigner
        String presignedUrl = generatePresignedUrl(playlistKey);

        // Cache the new presigned URL in Redis with an expiration time
        redisTemplate.opsForValue().set(
                cacheKey,
                presignedUrl,
                Duration.ofMinutes(presignedUrlExpiryInMinutes)
        );

        log.info("Streaming URL Generated and cached for movieId: {}", movieId);

        return StreamingResponse.builder()
                .movieId(movieId)
                .streamingUrl(presignedUrl)
                .quality("1080p, 720p, 480p")
                .expiresInMinutes(presignedUrlExpiryInMinutes)
                .build();
    }

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
}
