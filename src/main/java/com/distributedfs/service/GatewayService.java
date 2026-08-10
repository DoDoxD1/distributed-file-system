 
 package com.distributedfs.service;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.ChunkIntegrityException;
import com.distributedfs.error.DistributedFsException;
import com.distributedfs.error.DurabilityException;
import com.distributedfs.error.LogicalFileNotFoundException;
import com.distributedfs.error.PayloadTooLargeException;
import com.distributedfs.error.ReplicaUnavailableException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.ChunkRecord;
import com.distributedfs.model.ChunkWrite;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.util.ChunkingUtil;
import com.distributedfs.util.HashingUtil;
import com.distributedfs.util.LogicalPathValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates upload, download, delete, list, and version operations.
 */
public class GatewayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayService.class);

    private final MetadataService metadataService;
    private final Map<String, StorageNode> storageNodes;
    private final RackAwarePlacementStrategy placementStrategy;
    private final DistributedFsProperties properties;

    /**
     * Creates the gateway orchestrator.
     *
     * @param metadataService metadata authority
     * @param storageNodes available storage nodes
     * @param placementStrategy replica placement strategy
     * @param properties runtime settings
     */
    public GatewayService(
        MetadataService metadataService,
        Map<String, StorageNode> storageNodes,
        RackAwarePlacementStrategy placementStrategy,
        DistributedFsProperties properties
    ) {
        this.metadataService = metadataService;
        this.storageNodes = Map.copyOf(storageNodes);
        this.placementStrategy = placementStrategy;
        this.properties = properties;

        if (this.storageNodes.isEmpty()) {
            throw new ValidationException("storageNodes must include at least one node");
        }
        if (properties.getReplicationFactor() > this.storageNodes.size()) {
            throw new ValidationException(
                "replicationFactor cannot exceed available node count: "
                    + properties.getReplicationFactor() + " > " + this.storageNodes.size()
            );
        }
    }

    /**
     * Fetches manifest metadata for one path and optional version.
     *
     * @param logicalPath file path
     * @param versionId optional explicit version
     * @param includeDeleted whether deleted versions may be returned
     * @return manifest metadata
     */
    public FileManifest getManifest(String logicalPath, String versionId, boolean includeDeleted) {
        String normalizedPath = normalizeLogicalPath(logicalPath);
        String normalizedVersionId = normalizeNullable(versionId);
        return metadataService.getManifest(normalizedPath, normalizedVersionId, includeDeleted);
    }

    /**
     * Uploads payload and atomically publishes a new file version.
     *
     * @param logicalPath absolute logical path
     * @param payload file payload bytes
     * @param idempotencyKey optional idempotency key for safe retries
     * @return committed file manifest
     */
    public FileManifest uploadFile(String logicalPath, byte[] payload, String idempotencyKey) {
        String normalizedPath = normalizeLogicalPath(logicalPath);
        byte[] normalizedPayload = normalizePayload(payload);
        validatePayloadSize(normalizedPayload);
        String normalizedIdempotencyKey = normalizeNullableNonBlank(
            idempotencyKey,
            "idempotencyKey"
        );

        if (normalizedIdempotencyKey != null) {
            Optional<FileManifest> existingManifest = metadataService.findManifestByIdempotency(
                normalizedPath,
                normalizedIdempotencyKey
            );
            if (existingManifest.isPresent()) {
                FileManifest manifest = existingManifest.get();
                LOGGER.info(
                    "upload idempotency hit: logical_path={}, version_id={}",
                    normalizedPath,
                    manifest.versionId()
                );
                return manifest;
            }
        }

        List<byte[]> chunks = ChunkingUtil.splitIntoChunks(
            normalizedPayload,
            properties.getChunkSizeBytes()
        );
        List<ChunkWrite> chunkWrites = new ArrayList<>();
        Map<String, Set<String>> persistedChunkCache = new HashMap<>();

        for (byte[] chunkPayload : chunks) {
            String chunkChecksum = HashingUtil.sha256Hex(chunkPayload);
            String chunkId = chunkChecksum;

            Set<String> durableReplicaNodeIds = persistedChunkCache.get(chunkId);
            if (durableReplicaNodeIds == null) {
                durableReplicaNodeIds = writeReplicatedChunk(chunkId, chunkPayload, chunkChecksum);
                persistedChunkCache.put(chunkId, Set.copyOf(durableReplicaNodeIds));
            }

            chunkWrites.add(new ChunkWrite(
                chunkId,
                chunkChecksum,
                chunkPayload.length,
                Set.copyOf(durableReplicaNodeIds)
            ));
        }

        String fileChecksum = HashingUtil.sha256Hex(normalizedPayload);
        FileManifest manifest = metadataService.commitManifest(
            normalizedPath,
            chunkWrites,
            normalizedPayload.length,
            fileChecksum,
            normalizedIdempotencyKey
        );
        LOGGER.info(
            "upload committed: logical_path={}, version_id={}, chunk_count={}",
            normalizedPath,
            manifest.versionId(),
            manifest.chunkIds().size()
        );
        return manifest;
    }

    /**
     * Downloads one file version and verifies checksums.
     *
     * @param logicalPath file path
     * @param versionId optional explicit version
     * @return reconstructed payload
     */
    public byte[] downloadFile(String logicalPath, String versionId) {
        String normalizedPath = normalizeLogicalPath(logicalPath);
        String normalizedVersionId = normalizeNullable(versionId);

        FileManifest manifest = metadataService.getManifest(
            normalizedPath,
            normalizedVersionId,
            false
        );

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (String chunkId : manifest.chunkIds()) {
                ChunkRecord chunkRecord = metadataService.getChunkRecord(chunkId);
                byte[] chunkPayload = readChunkFromReplicas(chunkId, chunkRecord.replicaNodeIds());
                String actualChunkChecksum = HashingUtil.sha256Hex(chunkPayload);
                if (!actualChunkChecksum.equals(chunkRecord.checksum())) {
                    throw new ChunkIntegrityException(
                        "Checksum mismatch for chunk " + chunkId + ": "
                            + actualChunkChecksum + " != " + chunkRecord.checksum()
                    );
                }
                outputStream.writeBytes(chunkPayload);
            }

            byte[] payload = outputStream.toByteArray();
            String actualFileChecksum = HashingUtil.sha256Hex(payload);
            if (!actualFileChecksum.equals(manifest.checksum())) {
                throw new ChunkIntegrityException(
                    "File checksum mismatch for " + normalizedPath + "@" + manifest.versionId()
                        + ": " + actualFileChecksum + " != " + manifest.checksum()
                );
            }
            return payload;
        } catch (IOException error) {
            throw new ChunkIntegrityException(
                "Failed to reconstruct payload for " + normalizedPath,
                error
            );
        }
    }

    /**
     * Tombstones one version (or latest active version when absent).
     *
     * @param logicalPath file path
     * @param versionId optional explicit version
     * @return deleted manifest
     */
    public FileManifest deleteFile(String logicalPath, String versionId) {
        String normalizedPath = normalizeLogicalPath(logicalPath);
        String normalizedVersionId = normalizeNullable(versionId);
        return metadataService.markDeleted(normalizedPath, normalizedVersionId);
    }

    /**
     * Lists files by optional path prefix.
     *
     * @param prefix optional absolute path prefix
     * @return listing entries
     */
    public List<FileListing> listFiles(String prefix) {
        String normalizedPrefix = normalizePrefix(prefix);
        return metadataService.listFiles(normalizedPrefix);
    }

    /**
     * Lists active versions for one logical path.
     *
     * @param logicalPath file path
     * @return ordered active versions
     */
    public List<FileManifest> listVersions(String logicalPath) {
        String normalizedPath = normalizeLogicalPath(logicalPath);
        List<FileManifest> versions = metadataService.listVersions(normalizedPath);
        if (versions.isEmpty()) {
            metadataService.getManifest(normalizedPath, null, true);
            return versions;
        }
        return versions;
    }

    private Set<String> writeReplicatedChunk(String chunkId, byte[] payload, String checksum) {
        int replicationFactor = properties.getReplicationFactor();

        Optional<ChunkRecord> existingChunkRecord = metadataService.getChunkRecordOrEmpty(chunkId);
        Set<String> durableReplicaNodeIds = new HashSet<>();
        existingChunkRecord.ifPresent(
            chunkRecord -> durableReplicaNodeIds.addAll(chunkRecord.replicaNodeIds())
        );

        if (durableReplicaNodeIds.size() >= replicationFactor) {
            return Set.copyOf(durableReplicaNodeIds);
        }

        Set<String> attemptedNodeIds = new HashSet<>();
        Collection<StorageNode> allNodes = storageNodes.values();

        while (durableReplicaNodeIds.size() < replicationFactor) {
            Set<String> excludedNodeIds = new HashSet<>(durableReplicaNodeIds);
            excludedNodeIds.addAll(attemptedNodeIds);

            long healthyRemaining = allNodes.stream()
                .filter(StorageNode::isHealthy)
                .filter(node -> !excludedNodeIds.contains(node.nodeId()))
                .count();
            if (healthyRemaining == 0) {
                break;
            }

            StorageNode candidateNode = placementStrategy
                .chooseNodes(allNodes, 1, excludedNodeIds)
                .getFirst();
            attemptedNodeIds.add(candidateNode.nodeId());

            try {
                candidateNode.writeChunk(chunkId, payload, checksum);
                durableReplicaNodeIds.add(candidateNode.nodeId());
            } catch (DistributedFsException error) {
                LOGGER.warn(
                    "chunk replica write failed: chunk_id={}, node_id={}, reason={}",
                    chunkId,
                    candidateNode.nodeId(),
                    error.getMessage()
                );
            }
        }

        if (durableReplicaNodeIds.size() < replicationFactor) {
            throw new DurabilityException(
                "Chunk " + chunkId + " durability unmet: " + durableReplicaNodeIds.size()
                    + "/" + replicationFactor + " replicas"
            );
        }

        return durableReplicaNodeIds;
    }

    private byte[] readChunkFromReplicas(String chunkId, Set<String> replicaNodeIds) {
        if (replicaNodeIds.isEmpty()) {
            throw new ReplicaUnavailableException(
                "Chunk " + chunkId + " has no registered replicas"
            );
        }

        List<String> sortedNodeIds = replicaNodeIds.stream().sorted().toList();
        for (String nodeId : sortedNodeIds) {
            StorageNode node = storageNodes.get(nodeId);
            if (node == null) {
                LOGGER.warn(
                    "chunk replica references unknown node: chunk_id={}, node_id={}",
                    chunkId,
                    nodeId
                );
                continue;
            }

            try {
                return node.readChunk(chunkId);
            } catch (DistributedFsException error) {
                LOGGER.warn(
                    "chunk replica read failed: chunk_id={}, node_id={}, reason={}",
                    chunkId,
                    nodeId,
                    error.getMessage()
                );
            }
        }

        throw new ReplicaUnavailableException(
            "No readable replica available for chunk " + chunkId
        );
    }

    private static String normalizeLogicalPath(String logicalPath) {
        return LogicalPathValidator.normalizeLogicalPath(logicalPath);
    }

    private static String normalizePrefix(String prefix) {
        return LogicalPathValidator.normalizePrefix(prefix);
    }

    private static byte[] normalizePayload(byte[] payload) {
        if (payload == null) {
            throw new ValidationException("payload must be non-null");
        }
        return payload;
    }

    private void validatePayloadSize(byte[] payload) {
        long maxFileSizeBytes = properties.getMaxFileSizeBytes();
        if (payload.length > maxFileSizeBytes) {
            throw new PayloadTooLargeException(
                "Upload payload exceeds maximum allowed size: "
                    + payload.length + " > " + maxFileSizeBytes
            );
        }
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeNullableNonBlank(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new ValidationException(fieldName + " must be non-empty when provided");
        }
        return normalized;
    }
}
