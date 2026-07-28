package com.distributedfs.api.dto;

/**
 * API response for delete/tombstone operations.
 */
public record DeleteFileResponse(
    FileManifestResponse deletedManifest
) {
}
