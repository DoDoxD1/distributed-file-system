package com.distributedfs.api.dto;

import java.time.Instant;

/**
 * Standard API error response payload.
 */
public record ErrorResponse(
    Instant timestamp,
    String error,
    String message,
    String path
) {
}
