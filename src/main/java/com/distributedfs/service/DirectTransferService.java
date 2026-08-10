package com.distributedfs.service;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.PayloadTooLargeException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.DirectUploadSessionStatus;
import com.distributedfs.model.StoredObject;
import com.distributedfs.util.LogicalPathUtil;
import com.distributedfs.util.LogicalPathValidator;
import com.distributedfs.util.TimeProvider;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class DirectTransferService {

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private final MetadataService metadataService;
    private final DistributedFsProperties properties;
    private final TimeProvider timeProvider;

    public DirectTransferService(
        MetadataService metadataService,
        DistributedFsProperties properties,
        TimeProvider timeProvider
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService must be non-null");
        this.properties = Objects.requireNonNull(properties, "properties must be non-null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider must be non-null");
    }

    public DirectUploadSession createUploadSession(
        AuthenticatedUser user,
        String logicalPath,
        String checksumSha256,
        long sizeBytes,
        String contentType,
        String idempotencyKey
    ) {
        AuthenticatedUser authenticatedUser = requireUser(user);
        String normalizedLogicalPath = LogicalPathValidator.normalizeLogicalPath(logicalPath);
        String scopedLogicalPath = LogicalPathUtil.toScopedPath(
            authenticatedUser.userId(),
            normalizedLogicalPath
        );
        String normalizedChecksumSha256 = normalizeChecksumSha256(checksumSha256);
        long normalizedSizeBytes = validateSize(sizeBytes);
        String normalizedContentType = normalizeNullable(contentType);
        String normalizedIdempotencyKey = normalizeNullableNonBlank(idempotencyKey, "idempotencyKey");

        if (normalizedIdempotencyKey != null) {
            Optional<DirectUploadSession> existingSession = metadataService.findUploadSessionByIdempotency(
                authenticatedUser.userId(),
                scopedLogicalPath,
                normalizedIdempotencyKey
            );
            if (existingSession.isPresent()) {
                return toPublicSession(authenticatedUser.userId(), existingSession.get());
            }
        }

        Optional<StoredObject> existingStoredObject = metadataService.findStoredObject(
            authenticatedUser.userId(),
            normalizedChecksumSha256,
            normalizedSizeBytes
        );
        String sessionId = newSessionId();
        DirectUploadSessionStatus status = existingStoredObject.isPresent()
            ? DirectUploadSessionStatus.READY_TO_COMMIT
            : DirectUploadSessionStatus.AWAITING_UPLOAD;
        String stagingObjectKey = existingStoredObject.isPresent()
            ? null
            : buildStagingObjectKey(authenticatedUser.userId(), sessionId);
        String resolvedObjectId = existingStoredObject.map(StoredObject::objectId).orElse(null);
        Instant createdAt = timeProvider.now();
        Instant expiresAt = createdAt.plusSeconds(properties.getDirectUploadSessionTtlSeconds());

        DirectUploadSession createdSession = metadataService.createUploadSession(
            sessionId,
            authenticatedUser.userId(),
            scopedLogicalPath,
            normalizedChecksumSha256,
            normalizedSizeBytes,
            normalizedContentType,
            normalizedIdempotencyKey,
            stagingObjectKey,
            status,
            resolvedObjectId,
            createdAt,
            expiresAt
        );
        return toPublicSession(authenticatedUser.userId(), createdSession);
    }

    public DirectUploadSession getUploadSession(AuthenticatedUser user, String sessionId) {
        AuthenticatedUser authenticatedUser = requireUser(user);
        DirectUploadSession session = metadataService.getUploadSession(
            authenticatedUser.userId(),
            sessionId
        );
        return toPublicSession(authenticatedUser.userId(), session);
    }

    private AuthenticatedUser requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new ValidationException("authenticated user must be present");
        }
        return user;
    }

    private long validateSize(long sizeBytes) {
        if (sizeBytes < 0) {
            throw new ValidationException("sizeBytes must be non-negative, got " + sizeBytes);
        }
        long maxFileSizeBytes = properties.getMaxFileSizeBytes();
        if (sizeBytes > maxFileSizeBytes) {
            throw new PayloadTooLargeException(
                "Upload payload exceeds maximum allowed size: "
                    + sizeBytes + " > " + maxFileSizeBytes
            );
        }
        return sizeBytes;
    }

    private String normalizeChecksumSha256(String checksumSha256) {
        if (checksumSha256 == null || checksumSha256.isBlank()) {
            throw new ValidationException("checksumSha256 must be non-empty");
        }
        String normalizedChecksumSha256 = checksumSha256.strip().toLowerCase();
        if (!SHA256_HEX_PATTERN.matcher(normalizedChecksumSha256).matches()) {
            throw new ValidationException(
                "checksumSha256 must be a 64-character lowercase hexadecimal SHA-256"
            );
        }
        return normalizedChecksumSha256;
    }

    private DirectUploadSession toPublicSession(String userId, DirectUploadSession session) {
        return new DirectUploadSession(
            session.sessionId(),
            userId,
            LogicalPathUtil.toPublicPath(userId, session.logicalPath()),
            session.checksumSha256(),
            session.sizeBytes(),
            session.contentType(),
            session.idempotencyKey(),
            session.stagingObjectKey(),
            session.status(),
            session.resolvedObjectId(),
            session.createdAt(),
            session.expiresAt()
        );
    }

    private String buildStagingObjectKey(String userId, String sessionId) {
        return "users/" + userId + "/staging/" + sessionId;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeNullableNonBlank(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new ValidationException(fieldName + " must be non-empty when provided");
        }
        return normalized;
    }

    private String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
