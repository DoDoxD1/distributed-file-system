package com.distributedfs.model;

import java.time.Instant;

public record StoredObject(
    String objectId,
    String ownerUserId,
    String checksumSha256,
    long sizeBytes,
    String objectKey,
    long referenceCount,
    Instant createdAt
) {
}
