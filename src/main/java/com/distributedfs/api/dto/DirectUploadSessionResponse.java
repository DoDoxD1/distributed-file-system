package com.distributedfs.api.dto;

import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.DirectUploadSessionStatus;
import java.time.Instant;

public record DirectUploadSessionResponse(
    String sessionId,
    String ownerUserId,
    String logicalPath,
    String checksumSha256,
    long sizeBytes,
    String contentType,
    String idempotencyKey,
    String stagingObjectKey,
    DirectUploadSessionStatus status,
    boolean uploadRequired,
    Instant createdAt,
    Instant expiresAt
) {

    public static DirectUploadSessionResponse fromSession(DirectUploadSession session) {
        return new DirectUploadSessionResponse(
            session.sessionId(),
            session.ownerUserId(),
            session.logicalPath(),
            session.checksumSha256(),
            session.sizeBytes(),
            session.contentType(),
            session.idempotencyKey(),
            session.stagingObjectKey(),
            session.status(),
            session.uploadRequired(),
            session.createdAt(),
            session.expiresAt()
        );
    }
}
