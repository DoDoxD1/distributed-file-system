package com.distributedfs.service;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.PayloadTooLargeException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.DirectUploadSessionStatus;
import com.distributedfs.model.DirectUploadTarget;
import com.distributedfs.model.FileManifest;
import com.distributedfs.model.ObjectStorageObjectInfo;
import com.distributedfs.model.StoredObject;
import com.distributedfs.util.LogicalPathUtil;
import com.distributedfs.util.LogicalPathValidator;
import com.distributedfs.util.TimeProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class DirectTransferService {

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[a-f0-9]{64}$");
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CHECKSUM_METADATA_KEY = "sha256";
    private static final String CHECKSUM_METADATA_HEADER = "opc-meta-" + CHECKSUM_METADATA_KEY;

    private final MetadataService metadataService;
    private final DistributedFsProperties properties;
    private final OracleObjectStorageBucketClient bucketClient;
    private final TimeProvider timeProvider;

    public DirectTransferService(
        MetadataService metadataService,
        DistributedFsProperties properties,
        OracleObjectStorageBucketClient bucketClient,
        TimeProvider timeProvider
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService must be non-null");
        this.properties = Objects.requireNonNull(properties, "properties must be non-null");
        this.bucketClient = bucketClient;
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

    public DirectUploadTarget getUploadTarget(DirectUploadSession session) {
        if (session == null || !session.uploadRequired() || bucketClient == null) {
            return null;
        }
        DirectUploadTarget rawTarget = bucketClient.createUploadTarget(
            session.stagingObjectKey(),
            session.expiresAt()
        );
        Map<String, String> headers = new LinkedHashMap<>(rawTarget.headers());
        headers.put(CHECKSUM_METADATA_HEADER, session.checksumSha256());
        if (session.contentType() != null) {
            headers.put(CONTENT_TYPE_HEADER, session.contentType());
        }
        return new DirectUploadTarget(rawTarget.url(), rawTarget.method(), headers);
    }

    public FileManifest finalizeUploadSession(AuthenticatedUser user, String sessionId) {
        AuthenticatedUser authenticatedUser = requireUser(user);
        DirectUploadSession session = metadataService.getUploadSession(
            authenticatedUser.userId(),
            sessionId
        );
        if (session.committedVersionId() != null) {
            return toPublicManifest(
                authenticatedUser.userId(),
                metadataService.getManifest(session.logicalPath(), session.committedVersionId(), true)
            );
        }
        if (session.expiresAt().isBefore(timeProvider.now())) {
            throw new ValidationException(
                "Direct upload session " + session.sessionId() + " has expired"
            );
        }

        String resolvedObjectId = session.resolvedObjectId();
        if (resolvedObjectId == null) {
            resolvedObjectId = resolveUploadedObject(session);
        }

        FileManifest committedManifest = metadataService.commitDirectUploadSession(
            authenticatedUser.userId(),
            session.sessionId(),
            resolvedObjectId,
            properties.getMaxUserStorageBytes()
        );
        if (bucketClient != null && session.stagingObjectKey() != null) {
            bucketClient.deleteObject(session.stagingObjectKey());
        }
        return toPublicManifest(authenticatedUser.userId(), committedManifest);
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
            session.committedVersionId(),
            session.createdAt(),
            session.expiresAt()
        );
    }

    private FileManifest toPublicManifest(String userId, FileManifest manifest) {
        return new FileManifest(
            manifest.fileId(),
            userId,
            LogicalPathUtil.toPublicPath(userId, manifest.logicalPath()),
            manifest.versionId(),
            manifest.chunkIds(),
            manifest.sizeBytes(),
            manifest.checksum(),
            manifest.createdAt(),
            manifest.idempotencyKey(),
            manifest.deletedAt()
        );
    }

    private String resolveUploadedObject(DirectUploadSession session) {
        if (bucketClient == null) {
            throw new ValidationException(
                "Direct upload finalization requires the oracle-object-storage backend"
            );
        }
        Optional<ObjectStorageObjectInfo> stagedObjectInfo = bucketClient.findObjectInfo(
            session.stagingObjectKey()
        );
        if (stagedObjectInfo.isEmpty()) {
            throw new ValidationException(
                "Direct upload session " + session.sessionId() + " has no uploaded object to finalize"
            );
        }
        ObjectStorageObjectInfo objectInfo = stagedObjectInfo.get();
        if (objectInfo.sizeBytes() != session.sizeBytes()) {
            throw new ValidationException(
                "Uploaded object size does not match session plan: "
                    + objectInfo.sizeBytes() + " != " + session.sizeBytes()
            );
        }
        String uploadedChecksum = resolveUploadedChecksum(objectInfo);
        if (!session.checksumSha256().equals(uploadedChecksum)) {
            throw new ValidationException(
                "Uploaded object checksum does not match session plan: "
                    + uploadedChecksum + " != " + session.checksumSha256()
            );
        }
        String canonicalObjectKey = buildCanonicalObjectKey(
            session.ownerUserId(),
            session.checksumSha256(),
            session.sizeBytes()
        );
        if (!bucketClient.objectExists(canonicalObjectKey)) {
            bucketClient.copyObject(
                session.stagingObjectKey(),
                canonicalObjectKey,
                Map.of(CHECKSUM_METADATA_KEY, session.checksumSha256())
            );
        }
        return metadataService.createStoredObject(
            session.ownerUserId(),
            session.checksumSha256(),
            session.sizeBytes(),
            canonicalObjectKey,
            0
        ).objectId();
    }

    private String resolveUploadedChecksum(ObjectStorageObjectInfo objectInfo) {
        for (Map.Entry<String, String> entry : objectInfo.metadata().entrySet()) {
            if (CHECKSUM_METADATA_KEY.equalsIgnoreCase(entry.getKey())) {
                return normalizeChecksumSha256(entry.getValue());
            }
        }
        if (objectInfo.contentSha256() != null) {
            return normalizeChecksumSha256(objectInfo.contentSha256());
        }
        throw new ValidationException(
            "Uploaded object is missing required checksum metadata header "
                + CHECKSUM_METADATA_HEADER
        );
    }

    private String buildStagingObjectKey(String userId, String sessionId) {
        return "users/" + userId + "/staging/" + sessionId;
    }

    private String buildCanonicalObjectKey(String userId, String checksumSha256, long sizeBytes) {
        return "users/" + userId + "/objects/sha256/" + checksumSha256 + "/" + sizeBytes;
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
