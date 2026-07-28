package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.distributedfs.cluster.LocalCluster;
import com.distributedfs.cluster.LocalClusterFactory;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.LogicalFileNotFoundException;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-style tests for gateway upload/download/delete/list/version flows.
 */
class GatewayServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadDownloadAndVersionLookup() {
        LocalCluster cluster = buildCluster(
            4,
            2,
            0,
            3,
            List.of("rack-a", "rack-b", "rack-c")
        );

        byte[] payloadV1 = "hello distributed storage".getBytes();
        FileManifest manifestV1 = cluster.gatewayService()
            .uploadFile("/docs/report.txt", payloadV1, null);

        assertArrayEquals(
            payloadV1,
            cluster.gatewayService().downloadFile("/docs/report.txt", null)
        );

        byte[] payloadV2 = "hello distributed storage v2".getBytes();
        FileManifest manifestV2 = cluster.gatewayService()
            .uploadFile("/docs/report.txt", payloadV2, null);

        assertNotEquals(manifestV1.versionId(), manifestV2.versionId());
        assertArrayEquals(
            payloadV2,
            cluster.gatewayService().downloadFile("/docs/report.txt", null)
        );
        assertArrayEquals(
            payloadV1,
            cluster.gatewayService().downloadFile("/docs/report.txt", manifestV1.versionId())
        );

        List<FileListing> listings = cluster.gatewayService().listFiles("/docs");
        assertEquals(
            List.of("/docs/report.txt"),
            listings.stream().map(FileListing::logicalPath).toList()
        );
        assertEquals(manifestV2.versionId(), listings.getFirst().latestVersionId());

        List<FileManifest> versions = cluster.gatewayService().listVersions("/docs/report.txt");
        assertEquals(
            List.of(manifestV1.versionId(), manifestV2.versionId()),
            versions.stream().map(FileManifest::versionId).toList()
        );
    }

    @Test
    void idempotentUploadReplaysCommittedVersion() {
        LocalCluster cluster = buildCluster(
            4,
            2,
            0,
            3,
            List.of("rack-a", "rack-b", "rack-c")
        );

        FileManifest firstManifest = cluster.gatewayService().uploadFile(
            "/logs/app.log",
            "entry-1".getBytes(),
            "request-123"
        );
        FileManifest secondManifest = cluster.gatewayService().uploadFile(
            "/logs/app.log",
            "entry-2".getBytes(),
            "request-123"
        );

        assertEquals(firstManifest.versionId(), secondManifest.versionId());
        assertArrayEquals(
            "entry-1".getBytes(),
            cluster.gatewayService().downloadFile("/logs/app.log", null)
        );
    }

    @Test
    void deleteHidesLatestVersionFromActiveListing() {
        LocalCluster cluster = buildCluster(
            4,
            2,
            0,
            3,
            List.of("rack-a", "rack-b", "rack-c")
        );

        cluster.gatewayService().uploadFile("/tmp/a.bin", "abcdef".getBytes(), null);

        FileManifest deletedManifest = cluster.gatewayService().deleteFile("/tmp/a.bin", null);
        assertTrue(deletedManifest.isDeleted());

        assertThrows(
            LogicalFileNotFoundException.class,
            () -> cluster.gatewayService().downloadFile("/tmp/a.bin", null)
        );

        assertTrue(cluster.gatewayService().listFiles("/tmp").isEmpty());

        List<FileManifest> versions = cluster.gatewayService().listVersions("/tmp/a.bin");
        assertEquals(1, versions.size());
        assertTrue(versions.getFirst().isDeleted());
    }

    private LocalCluster buildCluster(
        int chunkSizeBytes,
        int replicationFactor,
        int gcRetentionSeconds,
        int nodeCount,
        List<String> failureDomains
    ) {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setChunkSizeBytes(chunkSizeBytes);
        properties.setReplicationFactor(replicationFactor);
        properties.setGcRetentionSeconds(gcRetentionSeconds);
        properties.setNodeCount(nodeCount);
        properties.setStorageRoot(tempDir.resolve("storage"));
        properties.setFailureDomains(failureDomains);

        return LocalClusterFactory.build(properties);
    }
}
