package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.distributedfs.cluster.LocalCluster;
import com.distributedfs.cluster.LocalClusterFactory;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.model.ChunkRecord;
import com.distributedfs.model.FileManifest;
import java.nio.file.Path;
import java.util.List;
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
