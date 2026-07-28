package com.distributedfs.model;

import java.time.Instant;
import java.util.List;

/**
 * Versioned immutable file metadata and chunk ordering.
 */
public record FileManifest(
    String fileId,
    String logicalPath,
    String versionId,
    List<String> chunkIds,
    long sizeBytes,
    String checksum,
    Instant createdAt,
    String idempotencyKey,
    Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
