package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.distributedfs.cluster.LocalCluster;
import com.distributedfs.cluster.LocalClusterFactory;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.model.ChunkRecord;
import com.distributedfs.model.FileManifest;
import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.util.HashingUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-style tests for scan/repair/garbage-collection worker flows.
 */
class BackgroundWorkerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void scanAndRepairRestoresReplication() {
        LocalCluster cluster = buildWorkerCluster();

        byte[] payload = "replicated-payload-replicated-payload-replicated-payload".getBytes();
        FileManifest manifest = cluster.gatewayService()
            .uploadFile("/archive/item.bin", payload, null);

        String chunkId = manifest.chunkIds().getFirst();
        ChunkRecord initialRecord = cluster.metadataService().getChunkRecord(chunkId);
        assertEquals(
            cluster.properties().getReplicationFactor(),
            initialRecord.replicaNodeIds().size()
        );

        String victimNodeId = initialRecord.replicaNodeIds().stream().sorted().toList().getFirst();
        cluster.storageNodes().get(victimNodeId).deleteChunk(chunkId);

        int removedReferences = cluster.backgroundWorkerService().scanAndPruneMissingReplicas();
        assertTrue(removedReferences >= 1);

        ChunkRecord postScanRecord = cluster.metadataService().getChunkRecord(chunkId);
        assertEquals(
            cluster.properties().getReplicationFactor() - 1,
            postScanRecord.replicaNodeIds().size()
        );

        int repairedReplicas = cluster.backgroundWorkerService().repairUnderReplicatedChunks();
        assertTrue(repairedReplicas >= 1);

        ChunkRecord postRepairRecord = cluster.metadataService().getChunkRecord(chunkId);
        assertEquals(
            cluster.properties().getReplicationFactor(),
            postRepairRecord.replicaNodeIds().size()
        );
        assertArrayEquals(
            payload,
            cluster.gatewayService().downloadFile("/archive/item.bin", null)
        );
    }

    @Test
    void garbageCollectRemovesUnreferencedChunks() {
        LocalCluster cluster = buildWorkerCluster();

        FileManifest manifest = cluster.gatewayService().uploadFile(
            "/delete/me.txt",
            "delete-me-now".getBytes(),
            null
        );
        FileManifest deletedManifest = cluster.gatewayService().deleteFile("/delete/me.txt", null);
        assertNotNull(deletedManifest.deletedAt());

        int removedChunks = cluster.backgroundWorkerService().garbageCollect(
            deletedManifest.deletedAt().plusSeconds(1)
        );
        Set<String> uniqueChunkIds = Set.copyOf(manifest.chunkIds());
        assertEquals(uniqueChunkIds.size(), removedChunks);

        for (String chunkId : uniqueChunkIds) {
            assertThrows(
                ChunkNotFoundException.class,
                () -> cluster.metadataService().getChunkRecord(chunkId)
            );
        }

        for (StorageNode node : cluster.storageNodes().values()) {
            for (String chunkId : uniqueChunkIds) {
                assertFalse(node.hasChunk(chunkId));
            }
        }
    }

    @Test
    void migrateLocalChunksToBucketMovesLegacyLocalFilesIntoOracleNodes() throws Exception {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setChunkSizeBytes(64);
        properties.setReplicationFactor(1);
        properties.setGcRetentionSeconds(0);
        properties.setNodeCount(2);
        properties.setStorageBackend(DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE);
        properties.setStorageRoot(tempDir.resolve("oracle-worker-storage"));
        properties.setFailureDomains(List.of("rack-a", "rack-b"));
        properties.getOracleObjectStorage().setNamespace("namespace");
        properties.getOracleObjectStorage().setBucket("bucket");
        properties.getOracleObjectStorage().setConfigFilePath("test-config-path");

        InMemoryOracleObjectStorageBucketClient bucketClient =
            new InMemoryOracleObjectStorageBucketClient();
        StorageNode nodeOne = new OracleObjectStorageNode(
            "node-1",
            "rack-a",
            bucketClient,
            "distributed-fs"
        );
        StorageNode nodeTwo = new OracleObjectStorageNode(
            "node-2",
            "rack-b",
            bucketClient,
            "distributed-fs"
        );

        byte[] payloadOne = "legacy-one".getBytes();
        byte[] payloadTwo = "legacy-two".getBytes();
        Path chunkFileOne = LocalStorageNode.resolveChunkPath(
            properties.getStorageRoot().resolve("node-1"),
            "chunk-1"
        );
        Path chunkFileTwo = LocalStorageNode.resolveChunkPath(
            properties.getStorageRoot().resolve("node-2"),
            "chunk-2"
        );
        Files.createDirectories(chunkFileOne.getParent());
        Files.createDirectories(chunkFileTwo.getParent());
        Files.write(chunkFileOne, payloadOne);
        Files.write(chunkFileTwo, payloadTwo);

        nodeTwo.writeChunk("chunk-2", payloadTwo, HashingUtil.sha256Hex(payloadTwo));

        BackgroundWorkerService workerService = new BackgroundWorkerService(
            mock(MetadataService.class),
            Map.of(
                "node-1", nodeOne,
                "node-2", nodeTwo
            ),
            new RackAwarePlacementStrategy(),
            properties
        );

        assertEquals(2, workerService.migrateLocalChunksToBucket());
        assertFalse(Files.exists(chunkFileOne));
        assertFalse(Files.exists(chunkFileTwo));
        assertArrayEquals(payloadOne, nodeOne.readChunk("chunk-1"));
        assertArrayEquals(payloadTwo, nodeTwo.readChunk("chunk-2"));
        assertEquals(0, workerService.migrateLocalChunksToBucket());
    }

    private LocalCluster buildWorkerCluster() {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setChunkSizeBytes(64);
        properties.setReplicationFactor(3);
        properties.setGcRetentionSeconds(0);
        properties.setNodeCount(4);
        properties.setStorageRoot(tempDir.resolve("worker-storage"));
        properties.setFailureDomains(List.of("rack-a", "rack-b", "rack-c", "rack-d"));

        return LocalClusterFactory.build(properties);
    }
}
