package com.distributedfs.model;

import java.time.Instant;
import java.util.Set;

/**
 * Chunk metadata including current replicas and version references.
 */
public record ChunkRecord(
    String chunkId,
    String checksum,
    int sizeBytes,
    Set<String> replicaNodeIds,
    Set<String> referencedVersionIds,
    Instant lastUnreferencedAt
) {
}
