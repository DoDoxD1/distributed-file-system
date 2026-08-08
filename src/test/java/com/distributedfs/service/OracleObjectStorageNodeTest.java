package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OracleObjectStorageNodeTest {

    @Test
    void storesReadsListsAndDeletesChunksThroughBucketClient() {
        InMemoryOracleObjectStorageBucketClient bucketClient =
            new InMemoryOracleObjectStorageBucketClient();
        OracleObjectStorageNode node = new OracleObjectStorageNode(
            "node-1",
            "rack-a",
            bucketClient,
            "distributed-fs"
        );

        byte[] payload = "chunk-data".getBytes();
        String checksum = com.distributedfs.util.HashingUtil.sha256Hex(payload);
        node.writeChunk("chunk-1", payload, checksum);

        assertTrue(node.hasChunk("chunk-1"));
        assertArrayEquals(payload, node.readChunk("chunk-1"));
        assertEquals(List.of("chunk-1"), node.listChunks());

        node.deleteChunk("chunk-1");
        assertTrue(node.listChunks().isEmpty());
    }

    @Test
    void trimsConfiguredObjectPrefixWhenBuildingObjectNames() {
        InMemoryOracleObjectStorageBucketClient bucketClient =
            new InMemoryOracleObjectStorageBucketClient();
        OracleObjectStorageNode node = new OracleObjectStorageNode(
            "node-2",
            "rack-b",
            bucketClient,
            "/prefix-root/"
        );

        byte[] payload = "chunk-data".getBytes();
        String checksum = com.distributedfs.util.HashingUtil.sha256Hex(payload);
        node.writeChunk("chunk-2", payload, checksum);

        assertEquals(
            List.of("prefix-root/nodes/node-2/chunks/chunk-2.chunk"),
            bucketClient.listObjectNames("")
        );
    }
}
