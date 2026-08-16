package com.distributedfs.model;

import java.time.Instant;

public record DirectUploadSession(
    String sessionId,
    String ownerUserId,
    String logicalPath,
    String checksumSha256,
    long sizeBytes,
    String contentType,
    String idempotencyKey,
    String stagingObjectKey,
    DirectUploadSessionStatus status,
    String resolvedObjectId,
    String committedVersionId,
    Instant createdAt,
    Instant expiresAt
) {
    public boolean uploadRequired() {
        return status == DirectUploadSessionStatus.AWAITING_UPLOAD;
    }
}
