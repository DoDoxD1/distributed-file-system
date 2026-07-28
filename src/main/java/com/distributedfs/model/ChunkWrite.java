package com.distributedfs.model;

import java.util.Set;

/**
 * Chunk write result used by gateway for manifest commit.
 */
public record ChunkWrite(
    String chunkId,
    String checksum,
    int sizeBytes,
    Set<String> replicaNodeIds
) {
}
