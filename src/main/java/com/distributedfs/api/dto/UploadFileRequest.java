package com.distributedfs.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for uploading one logical file version.
 */
public record UploadFileRequest(
    @NotBlank String logicalPath,
    @NotBlank String payloadBase64,
    String idempotencyKey
) {
}
