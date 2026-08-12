package com.streamx.streamingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StreamingResponse {

    private String movieId;
    private String streamingUrl;
    private String quality;
    private long expiresInMinutes;
}
