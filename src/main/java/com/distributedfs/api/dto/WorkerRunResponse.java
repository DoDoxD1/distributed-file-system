package com.distributedfs.api.dto;

/**
 * API response for worker execution endpoints.
 */
public record WorkerRunResponse(
    String worker,
    int affectedCount
) {
}
