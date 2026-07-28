package com.distributedfs.service;

import com.distributedfs.error.ChunkIntegrityException;
import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.error.DistributedFsException;
import com.distributedfs.error.NodeUnavailableException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.util.HashingUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local filesystem-backed storage node for immutable chunk replicas.
 */
public class StorageNode {

    private final String nodeId;
    private final String failureDomain;
    private final Path storageDirectory;
    private volatile boolean healthy;

    /**
     * Creates one local storage node.
     *
     * @param nodeId stable node identifier
     * @param failureDomain domain used for replica placement
     * @param storageDirectory local root directory for this node
     */
    public StorageNode(String nodeId, String failureDomain, Path storageDirectory) {
        this.nodeId = validateNodeId(nodeId);
        this.failureDomain = validateFailureDomain(failureDomain);
        this.storageDirectory = validateStorageDirectory(storageDirectory);
        this.healthy = true;

        try {
            Files.createDirectories(storageDirectory);
            Files.createDirectories(chunkDirectory());
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to initialize storage directories for node " + nodeId,
                error
            );
        }
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

        Path chunkPath = chunkPath(normalizedChunkId);
        if (Files.exists(chunkPath)) {
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

        try {
            Files.write(chunkPath, payload);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to persist chunk " + normalizedChunkId + " on node " + nodeId,
                error
            );
        }
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

        Path chunkPath = chunkPath(normalizedChunkId);
        if (!Files.exists(chunkPath)) {
            throw new ChunkNotFoundException(
                "Chunk " + normalizedChunkId + " does not exist on node " + nodeId
            );
        }

        try {
            return Files.readAllBytes(chunkPath);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to read chunk " + normalizedChunkId + " from node " + nodeId,
                error
            );
        }
    }

    /**
     * Returns whether a chunk exists physically on disk.
     *
     * @param chunkId chunk identifier
     * @return true when the chunk file exists
     */
    public synchronized boolean hasChunk(String chunkId) {
        String normalizedChunkId = validateChunkId(chunkId);
        return Files.exists(chunkPath(normalizedChunkId));
    }

    /**
     * Deletes one chunk file if present.
     *
     * @param chunkId chunk identifier
     */
    public synchronized void deleteChunk(String chunkId) {
        ensureAvailable("delete");
        String normalizedChunkId = validateChunkId(chunkId);

        Path chunkPath = chunkPath(normalizedChunkId);
        if (!Files.exists(chunkPath)) {
            return;
        }

        try {
            Files.delete(chunkPath);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to delete chunk " + normalizedChunkId + " from node " + nodeId,
                error
            );
        }
    }

    /**
     * Lists all chunk IDs stored on this node.
     *
     * @return sorted chunk identifiers
     */
    public synchronized List<String> listChunks() {
        try (Stream<Path> pathStream = Files.list(chunkDirectory())) {
            List<String> chunkIds = new ArrayList<>();
            pathStream
                .filter(path -> path.getFileName().toString().endsWith(".chunk"))
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    chunkIds.add(fileName.substring(0, fileName.length() - 6));
                });
            chunkIds.sort(Comparator.naturalOrder());
            return chunkIds;
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to list chunks on node " + nodeId,
                error
            );
        }
    }

    private void ensureAvailable(String operation) {
        if (!healthy) {
            throw new NodeUnavailableException(
                "Node " + nodeId + " is unavailable for operation " + operation
            );
        }
    }

    private Path chunkDirectory() {
        return storageDirectory.resolve("chunks");
    }

    private Path chunkPath(String chunkId) {
        return chunkDirectory().resolve(chunkId + ".chunk");
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

    private static Path validateStorageDirectory(Path storageDirectory) {
        if (storageDirectory == null) {
            throw new ValidationException("storageDirectory must be non-null");
        }
        return storageDirectory;
    }

    private static String validateChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new ValidationException("chunkId must be non-empty");
        }
        if (chunkId.contains("/") || chunkId.contains("\\")) {
            throw new ValidationException("chunkId contains path separator: " + chunkId);
        }
        return chunkId;
    }

    private static void validatePayload(byte[] payload) {
        if (payload == null) {
            throw new ValidationException("payload must be non-null");
        }
    }
}
