package com.distributedfs.service;

import com.distributedfs.error.ChunkIntegrityException;
import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.error.NodeUnavailableException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.util.HashingUtil;
import java.util.List;

/**
 * Storage node for immutable chunk replicas.
 */
public abstract class StorageNode {

    private final String nodeId;
    private final String failureDomain;
    private volatile boolean healthy;

    /**
     * Creates one storage node.
     *
     * @param nodeId stable node identifier
     * @param failureDomain domain used for replica placement
     */
    protected StorageNode(String nodeId, String failureDomain) {
        this.nodeId = validateNodeId(nodeId);
        this.failureDomain = validateFailureDomain(failureDomain);
        this.healthy = true;
    }

    public String nodeId() {
        return nodeId;
    }

    public String failureDomain() {
        return failureDomain;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void markHealthy() {
        healthy = true;
    }

    public void markUnhealthy() {
        healthy = false;
    }

    /**
     * Writes a chunk when absent and validates checksum before persistence.
     *
     * @param chunkId chunk identifier
     * @param payload binary payload
     * @param expectedChecksum expected SHA-256 checksum
     */
    public synchronized void writeChunk(String chunkId, byte[] payload, String expectedChecksum) {
        ensureAvailable("write");
        String normalizedChunkId = validateChunkId(chunkId);
        validatePayload(payload);

        String actualChecksum = HashingUtil.sha256Hex(payload);
        if (!actualChecksum.equals(expectedChecksum)) {
            throw new ChunkIntegrityException(
                "Checksum mismatch for chunk " + normalizedChunkId + ": "
                    + actualChecksum + " != " + expectedChecksum
            );
        }

        if (hasChunkInternal(normalizedChunkId)) {
            byte[] existingPayload = readChunk(normalizedChunkId);
            String existingChecksum = HashingUtil.sha256Hex(existingPayload);
            if (!existingChecksum.equals(expectedChecksum)) {
                throw new ChunkIntegrityException(
                    "Existing chunk " + normalizedChunkId + " on " + nodeId
                        + " has checksum " + existingChecksum
                        + ", expected " + expectedChecksum
                );
            }
            return;
        }

        writeChunkInternal(normalizedChunkId, payload);
    }

    /**
     * Reads a stored chunk payload.
     *
     * @param chunkId chunk identifier
     * @return chunk bytes
     */
    public synchronized byte[] readChunk(String chunkId) {
        ensureAvailable("read");
        String normalizedChunkId = validateChunkId(chunkId);

        if (!hasChunkInternal(normalizedChunkId)) {
            throw new ChunkNotFoundException(
                "Chunk " + normalizedChunkId + " does not exist on node " + nodeId
            );
        }

        return readChunkInternal(normalizedChunkId);
    }

    /**
     * Returns whether a chunk exists physically on disk.
     *
     * @param chunkId chunk identifier
     * @return true when the chunk file exists
     */
    public synchronized boolean hasChunk(String chunkId) {
        String normalizedChunkId = validateChunkId(chunkId);
        return hasChunkInternal(normalizedChunkId);
    }

    /**
     * Deletes one chunk file if present.
     *
     * @param chunkId chunk identifier
     */
    public synchronized void deleteChunk(String chunkId) {
        ensureAvailable("delete");
        String normalizedChunkId = validateChunkId(chunkId);

        if (!hasChunkInternal(normalizedChunkId)) {
            return;
        }

        deleteChunkInternal(normalizedChunkId);
    }

    /**
     * Lists all chunk IDs stored on this node.
     *
     * @return sorted chunk identifiers
     */
    public synchronized List<String> listChunks() {
        return listChunksInternal();
    }

    protected final void ensureAvailable(String operation) {
        if (!healthy) {
            throw new NodeUnavailableException(
                "Node " + nodeId + " is unavailable for operation " + operation
            );
        }
    }

    private static String validateNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new ValidationException("nodeId must be non-empty");
        }
        return nodeId;
    }

    private static String validateFailureDomain(String failureDomain) {
        if (failureDomain == null || failureDomain.isBlank()) {
            throw new ValidationException("failureDomain must be non-empty");
        }
        return failureDomain;
    }

    protected static String validateChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new ValidationException("chunkId must be non-empty");
        }
        if (chunkId.contains("/") || chunkId.contains("\\")) {
            throw new ValidationException("chunkId contains path separator: " + chunkId);
        }
        return chunkId;
    }

    protected static void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new ValidationException("payload must be non-null");
        }
    }

    protected abstract boolean hasChunkInternal(String chunkId);

    protected abstract void writeChunkInternal(String chunkId, byte[] payload);

    protected abstract byte[] readChunkInternal(String chunkId);

    protected abstract void deleteChunkInternal(String chunkId);

    protected abstract List<String> listChunksInternal();
}
