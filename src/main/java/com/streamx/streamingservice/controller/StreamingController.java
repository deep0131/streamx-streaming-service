package com.streamx.streamingservice.controller;

import com.streamx.streamingservice.dto.StreamingResponse;
import com.streamx.streamingservice.service.StreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/api/v1/stream")
@Slf4j
@RequiredArgsConstructor
public class StreamingController {

    private final StreamingService streamingService;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String MASTER_PLAYLIST_KEY_PREFIX = "streaming:playlist:";

    /**
     * Get streaming url for a movie.
     * Returns a presigned HLS URL
     *
     * GET /api/v1/stream/{movieId}
     */

    @GetMapping("/{movieId}")
    public ResponseEntity<StreamingResponse> getStreamingUrl(@PathVariable String movieId) {

        log.info("Getting streaming url for movieId: {}", movieId);

        // Check if the master playlist key is cached in Redis
        String cachedPlaylistKey = redisTemplate.opsForValue().get(MASTER_PLAYLIST_KEY_PREFIX + movieId);

        if (cachedPlaylistKey == null) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponse response = streamingService.getStreamingUrl(movieId, cachedPlaylistKey);

        return ResponseEntity.ok(response);
    }
}
