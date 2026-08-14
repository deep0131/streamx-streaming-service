package com.streamx.streamingservice.controller;

import com.streamx.streamingservice.dto.StreamingResponse;
import com.streamx.streamingservice.service.StreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stream")
@Slf4j
@RequiredArgsConstructor
public class StreamingController {

    private final StreamingService streamingService;

    /**
     * Get streaming url for a movie.
     * Returns a presigned HLS URL
     *
     * GET /api/v1/stream/{movieId}
     */

    @GetMapping("/{movieId}")
    public ResponseEntity<StreamingResponse> getStreamingUrl(@PathVariable String movieId) {

        log.info("Getting streaming url for movieId: {}", movieId);

        StreamingResponse response = streamingService.getStreamingUrl(movieId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{movieId}/playlist")
    public ResponseEntity<String> getSignedPlaylist(@PathVariable String movieId, @RequestParam("path") String path) {
        // Implementation for getting signed playlist
        String signedPlaylist= streamingService.getSignedPlaylist(movieId, path);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-mpegURL"))
                .body(signedPlaylist);
    }
}
