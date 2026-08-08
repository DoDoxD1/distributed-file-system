package com.distributedfs.service;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.DistributedFsException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.ChunkRecord;
import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.util.HashingUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs integrity scan, replica repair, and retention-based garbage collection.
 */
public class BackgroundWorkerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundWorkerService.class);

    private final MetadataService metadataService;
    private final java.util.Map<String, StorageNode> storageNodes;
    private final RackAwarePlacementStrategy placementStrategy;
    private final DistributedFsProperties properties;

    /**
     * Creates background worker service.
     *
     * @param metadataService metadata authority
     * @param storageNodes available storage nodes
     * @param placementStrategy replica placement strategy
     * @param properties runtime settings
     */
    public BackgroundWorkerService(
        MetadataService metadataService,
        java.util.Map<String, StorageNode> storageNodes,
        RackAwarePlacementStrategy placementStrategy,
        DistributedFsProperties properties
    ) {
        this.metadataService = metadataService;
        this.storageNodes = java.util.Map.copyOf(storageNodes);
        this.placementStrategy = placementStrategy;
        this.properties = properties;
    }

    /**
     * Scans chunk replicas, removes metadata references for missing/corrupt replicas.
     *
     * @return number of removed references
     */
    public int scanAndPruneMissingReplicas() {
        int removedReferences = 0;

        for (ChunkRecord chunkRecord : metadataService.listChunkRecords()) {
            for (String nodeId : chunkRecord.replicaNodeIds().stream().sorted().toList()) {
                StorageNode node = storageNodes.get(nodeId);
                if (node == null) {
                    metadataService.removeReplica(chunkRecord.chunkId(), nodeId);
                    removedReferences++;
                    continue;
                }

                try {
                    byte[] payload = node.readChunk(chunkRecord.chunkId());
                    String actualChecksum = HashingUtil.sha256Hex(payload);
                    if (!actualChecksum.equals(chunkRecord.checksum())) {
                        safelyDeleteCorruptedChunk(node, chunkRecord.chunkId(), nodeId);
                        metadataService.removeReplica(chunkRecord.chunkId(), nodeId);
                        removedReferences++;
                    }
                } catch (DistributedFsException error) {
                    metadataService.removeReplica(chunkRecord.chunkId(), nodeId);
                    removedReferences++;
                }
            }
        }

        return removedReferences;
    }

    /**
     * Repairs chunks whose replica count is below configured durability.
     *
     * @return number of new replica writes completed
     */
    public int repairUnderReplicatedChunks() {
        int repairedReplicas = 0;
        Collection<StorageNode> allNodes = storageNodes.values();

        for (ChunkRecord chunkRecord : metadataService.listChunkRecords()) {
            if (chunkRecord.referencedVersionIds().isEmpty()) {
                continue;
            }

            int missingReplicaCount =
                properties.getReplicationFactor() - chunkRecord.replicaNodeIds().size();
            if (missingReplicaCount <= 0) {
                continue;
            }

            Optional<byte[]> sourcePayloadOpt = readFromExistingReplica(
                chunkRecord.chunkId(),
                chunkRecord.replicaNodeIds()
            );
            if (sourcePayloadOpt.isEmpty()) {
                LOGGER.warn(
                    "cannot repair chunk without readable source replica: chunk_id={}",
                    chunkRecord.chunkId()
                );
                continue;
            }
            byte[] sourcePayload = sourcePayloadOpt.get();

            Set<String> excludedNodeIds = new HashSet<>(chunkRecord.replicaNodeIds());
            for (int index = 0; index < missingReplicaCount; index++) {
                long availableCount = allNodes.stream()
                    .filter(StorageNode::isHealthy)
                    .filter(node -> !excludedNodeIds.contains(node.nodeId()))
                    .count();
                if (availableCount == 0) {
                    break;
                }

                StorageNode targetNode;
                try {
                    targetNode = placementStrategy
                        .chooseNodes(allNodes, 1, excludedNodeIds)
                        .getFirst();
                } catch (ValidationException error) {
                    break;
                }
                excludedNodeIds.add(targetNode.nodeId());

                try {
                    targetNode.writeChunk(
                        chunkRecord.chunkId(),
                        sourcePayload,
                        chunkRecord.checksum()
                    );
                    metadataService.addReplica(chunkRecord.chunkId(), targetNode.nodeId());
                    repairedReplicas++;
                } catch (DistributedFsException error) {
                    LOGGER.warn(
                        "replica repair write failed: chunk_id={}, node_id={}, reason={}",
                        chunkRecord.chunkId(),
                        targetNode.nodeId(),
                        error.getMessage()
                    );
                }
            }
        }

        return repairedReplicas;
    }

    /**
     * Deletes unreferenced chunk data past retention and purges metadata records.
     *
     * @param referenceTime optional reference time; null means now
     * @return number of purged chunk records
     */
    public int garbageCollect(Instant referenceTime) {
        int removedChunks = 0;
        for (ChunkRecord chunkRecord : metadataService.getGarbageCollectionCandidates(
            properties.getGcRetentionSeconds(),
            referenceTime
        )) {
            for (String nodeId : chunkRecord.replicaNodeIds().stream().sorted().toList()) {
                StorageNode node = storageNodes.get(nodeId);
                if (node == null) {
                    continue;
                }
                try {
                    node.deleteChunk(chunkRecord.chunkId());
                } catch (DistributedFsException error) {
                    LOGGER.warn(
                        "skipping chunk delete on unavailable node: "
                            + "chunk_id={}, node_id={}, reason={}",
                        chunkRecord.chunkId(),
                        nodeId,
                        error.getMessage()
                    );
                }
            }

            metadataService.purgeChunkRecord(chunkRecord.chunkId());
            removedChunks++;
        }

        return removedChunks;
    }

    public int migrateLocalChunksToBucket() {
        if (!DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE.equals(
            properties.getStorageBackend()
        )) {
            throw new ValidationException(
                "Local chunk migration requires storageBackend="
                    + DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE
            );
        }

        int migratedChunks = 0;
        for (StorageNode node : storageNodes.values().stream().sorted(
            java.util.Comparator.comparing(StorageNode::nodeId)
        ).toList()) {
            if (!(node instanceof OracleObjectStorageNode)) {
                throw new DistributedFsException(
                    "Oracle Object Storage backend selected but node is not Oracle-backed: "
                        + node.nodeId()
                );
            }

            Path localNodeDirectory = properties.getStorageRoot().resolve(node.nodeId());
            Path localChunkDirectory = LocalStorageNode.resolveChunkDirectory(localNodeDirectory);
            if (!Files.isDirectory(localChunkDirectory)) {
                continue;
            }

            List<Path> chunkFiles;
            try (var pathStream = Files.list(localChunkDirectory)) {
                chunkFiles = pathStream
                    .filter(LocalStorageNode::isChunkFile)
                    .sorted()
                    .toList();
            } catch (IOException error) {
                throw new DistributedFsException(
                    "Failed to list local chunks for node " + node.nodeId(),
                    error
                );
            }

            for (Path chunkFile : chunkFiles) {
                migratedChunks += migrateLocalChunkToBucket(node, chunkFile);
            }
        }

        return migratedChunks;
    }

    private Optional<byte[]> readFromExistingReplica(String chunkId, Set<String> replicaNodeIds) {
        for (String nodeId : replicaNodeIds.stream().sorted().toList()) {
            StorageNode node = storageNodes.get(nodeId);
            if (node == null) {
                continue;
            }
            try {
                return Optional.of(node.readChunk(chunkId));
            } catch (DistributedFsException error) {
                continue;
            }
        }
        return Optional.empty();
    }

    private void safelyDeleteCorruptedChunk(StorageNode node, String chunkId, String nodeId) {
        try {
            node.deleteChunk(chunkId);
        } catch (DistributedFsException error) {
            LOGGER.warn(
                "unable to delete corrupted chunk due to node unavailability: "
                    + "chunk_id={}, node_id={}",
                chunkId,
                nodeId
            );
        }
    }

    private int migrateLocalChunkToBucket(StorageNode node, Path chunkFile) {
        String chunkId = LocalStorageNode.chunkIdFromChunkFile(chunkFile);
        byte[] payload;
        try {
            payload = Files.readAllBytes(chunkFile);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to read local chunk file for migration: " + chunkFile,
                error
            );
        }

        String checksum = HashingUtil.sha256Hex(payload);
        node.writeChunk(chunkId, payload, checksum);

        try {
            Files.delete(chunkFile);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to delete migrated local chunk file: " + chunkFile,
                error
            );
        }

        LOGGER.info(
            "migrated local chunk to bucket: chunk_id={}, node_id={}",
            chunkId,
            node.nodeId()
        );
        return 1;
    }
}
