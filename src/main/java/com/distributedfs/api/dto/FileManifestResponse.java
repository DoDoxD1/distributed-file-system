package com.distributedfs.api.dto;

import com.distributedfs.model.FileManifest;
import java.time.Instant;
import java.util.List;

/**
 * API response shape for one file manifest version.
 */
public record FileManifestResponse(
    String fileId,
    String ownerUserId,
    String logicalPath,
    String versionId,
    List<String> chunkIds,
    long sizeBytes,
    String checksum,
    Instant createdAt,
    String idempotencyKey,
    Instant deletedAt,
    boolean deleted
) {

    public static FileManifestResponse fromManifest(FileManifest manifest) {
        return new FileManifestResponse(
            manifest.fileId(),
            manifest.ownerUserId(),
            manifest.logicalPath(),
            manifest.versionId(),
            List.copyOf(manifest.chunkIds()),
            manifest.sizeBytes(),
            manifest.checksum(),
            manifest.createdAt(),
            manifest.idempotencyKey(),
            manifest.deletedAt(),
            manifest.isDeleted()
        );
    }
}
