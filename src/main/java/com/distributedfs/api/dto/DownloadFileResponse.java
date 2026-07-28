package com.distributedfs.api.dto;

/**
 * API response for one downloaded payload as base64.
 */
public record DownloadFileResponse(
    String logicalPath,
    String versionId,
    String payloadBase64
) {
}
