package com.distributedfs.api.dto;

import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.DirectUploadSessionStatus;
import com.distributedfs.model.DirectUploadTarget;
import java.time.Instant;
import java.util.Map;

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
    String committedVersionId,
    boolean uploadRequired,
    String uploadUrl,
    String uploadMethod,
    Map<String, String> uploadHeaders,
    Instant createdAt,
    Instant expiresAt
) {

    public static DirectUploadSessionResponse fromSession(
        DirectUploadSession session,
        DirectUploadTarget uploadTarget
    ) {
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
            session.committedVersionId(),
            session.uploadRequired(),
            uploadTarget == null ? null : uploadTarget.url(),
            uploadTarget == null ? null : uploadTarget.method(),
            uploadTarget == null ? Map.of() : uploadTarget.headers(),
            session.createdAt(),
            session.expiresAt()
        );
    }
}
