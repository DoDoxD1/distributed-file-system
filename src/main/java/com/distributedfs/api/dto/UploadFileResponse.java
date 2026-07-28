package com.distributedfs.api.dto;

/**
 * API response for successful upload operations.
 */
public record UploadFileResponse(
    FileManifestResponse manifest
) {
}
