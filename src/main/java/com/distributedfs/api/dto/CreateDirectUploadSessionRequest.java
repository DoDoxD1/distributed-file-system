package com.distributedfs.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateDirectUploadSessionRequest(
    @NotBlank String logicalPath,
    @NotBlank String checksumSha256,
    @Min(0) long sizeBytes,
    String contentType,
    String idempotencyKey
) {
}
