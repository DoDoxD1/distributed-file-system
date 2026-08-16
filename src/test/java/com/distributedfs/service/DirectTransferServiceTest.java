package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.distributedfs.cluster.LocalCluster;
import com.distributedfs.cluster.LocalClusterFactory;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.DirectUploadTarget;
import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.DirectUploadSessionStatus;
import com.distributedfs.model.FileManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectTransferServiceTest {

    private static final String SAMPLE_SHA256 =
        "8f434346648f6b96df89dda901c5176b10a6d83961f9778f7f1449d84d35a32c";

    @TempDir
    Path tempDir;

    @Test
    void createUploadSessionPlansScopedStagingObjectWhenUploadIsRequired() {
        InMemoryOracleObjectStorageBucketClient bucketClient = new InMemoryOracleObjectStorageBucketClient();
        LocalCluster cluster = buildOracleCluster(bucketClient);
        AuthenticatedUser user = cluster.authenticationService().register(
            "direct-upload@example.com",
            "password123"
        ).user();

        DirectUploadSession session = cluster.directTransferService().createUploadSession(
            user,
            "/docs/report.pdf",
            SAMPLE_SHA256,
            128,
            "application/pdf",
            null
        );

        assertEquals(user.userId(), session.ownerUserId());
        assertEquals("/docs/report.pdf", session.logicalPath());
        assertEquals(DirectUploadSessionStatus.AWAITING_UPLOAD, session.status());
        assertTrue(session.uploadRequired());
        assertNotNull(session.stagingObjectKey());
        assertTrue(session.stagingObjectKey().startsWith("users/" + user.userId() + "/staging/"));
        assertNotNull(session.expiresAt());
        DirectUploadTarget uploadTarget = cluster.directTransferService().getUploadTarget(session);
        assertNotNull(uploadTarget);
        assertEquals("PUT", uploadTarget.method());
        assertEquals(
            SAMPLE_SHA256,
            uploadTarget.headers().get("opc-meta-sha256")
        );
        assertTrue(uploadTarget.url().contains(session.stagingObjectKey()));

        DirectUploadSession loadedSession = cluster.directTransferService().getUploadSession(
            user,
            session.sessionId()
        );
        assertEquals(session, loadedSession);
    }

    @Test
    void createUploadSessionReplaysCommittedPlanForSameIdempotencyKey() {
        LocalCluster cluster = buildCluster();
        AuthenticatedUser user = cluster.authenticationService().register(
            "idempotent-direct@example.com",
            "password123"
        ).user();

        DirectUploadSession firstSession = cluster.directTransferService().createUploadSession(
            user,
            "/docs/report.pdf",
            SAMPLE_SHA256,
            128,
            "application/pdf",
            "request-123"
        );
        DirectUploadSession replayedSession = cluster.directTransferService().createUploadSession(
            user,
            "/docs/report.pdf",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            256,
            "application/octet-stream",
            "request-123"
        );

        assertEquals(firstSession, replayedSession);
    }

    @Test
    void createUploadSessionSkipsUploadWhenStoredObjectAlreadyExistsForUser() {
        InMemoryOracleObjectStorageBucketClient bucketClient = new InMemoryOracleObjectStorageBucketClient();
        LocalCluster cluster = buildOracleCluster(bucketClient);
        AuthenticatedUser user = cluster.authenticationService().register(
            "dedup-direct@example.com",
            "password123"
        ).user();
        cluster.metadataService().createStoredObject(
            user.userId(),
            SAMPLE_SHA256,
            128,
            "users/" + user.userId() + "/objects/sha256/" + SAMPLE_SHA256,
            1
        );

        DirectUploadSession session = cluster.directTransferService().createUploadSession(
            user,
            "/docs/report.pdf",
            SAMPLE_SHA256,
            128,
            "application/pdf",
            null
        );

        assertEquals(DirectUploadSessionStatus.READY_TO_COMMIT, session.status());
        assertFalse(session.uploadRequired());
        assertNull(session.stagingObjectKey());
        assertNotNull(session.expiresAt());
        assertNull(cluster.directTransferService().getUploadTarget(session));
    }

    @Test
    void finalizeUploadSessionCommitsObjectBackedVersionAndDeletesStagingObject() {
        InMemoryOracleObjectStorageBucketClient bucketClient = new InMemoryOracleObjectStorageBucketClient();
        LocalCluster cluster = buildOracleCluster(bucketClient);
        AuthenticatedUser user = cluster.authenticationService().register(
            "finalize-direct@example.com",
            "password123"
        ).user();

        DirectUploadSession session = cluster.directTransferService().createUploadSession(
            user,
            "/docs/report.pdf",
            SAMPLE_SHA256,
            3,
            "application/pdf",
            "finalize-1"
        );
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        bucketClient.putObject(
            session.stagingObjectKey(),
            payload,
            "application/pdf",
            Map.of("sha256", SAMPLE_SHA256)
        );

        FileManifest manifest = cluster.directTransferService().finalizeUploadSession(
            user,
            session.sessionId()
        );

        assertEquals("/docs/report.pdf", manifest.logicalPath());
        assertNotNull(manifest.versionId());
        assertTrue(manifest.chunkIds().isEmpty());
        assertFalse(bucketClient.objectExists(session.stagingObjectKey()));
        assertTrue(
            bucketClient.objectExists(
                "users/" + user.userId() + "/objects/sha256/" + SAMPLE_SHA256 + "/3"
            )
        );
        assertArrayEquals(
            payload,
            cluster.userFileService().downloadFile(user, "/docs/report.pdf", manifest.versionId())
        );

        DirectUploadSession completedSession = cluster.directTransferService().getUploadSession(
            user,
            session.sessionId()
        );
        assertEquals(DirectUploadSessionStatus.COMPLETED, completedSession.status());
        assertEquals(manifest.versionId(), completedSession.committedVersionId());
    }

    private LocalCluster buildCluster() {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setChunkSizeBytes(64);
        properties.setReplicationFactor(3);
        properties.setGcRetentionSeconds(0);
        properties.setNodeCount(4);
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(86_400);
        properties.setDirectUploadSessionTtlSeconds(900);
        properties.setStorageRoot(tempDir.resolve("direct-transfer-storage"));
        properties.setFailureDomains(List.of("rack-a", "rack-b", "rack-c", "rack-d"));

        return LocalClusterFactory.build(properties);
    }

    private LocalCluster buildOracleCluster(InMemoryOracleObjectStorageBucketClient bucketClient) {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setStorageBackend(DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE);
        properties.setChunkSizeBytes(64);
        properties.setReplicationFactor(3);
        properties.setGcRetentionSeconds(0);
        properties.setNodeCount(4);
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(86_400);
        properties.setDirectUploadSessionTtlSeconds(900);
        properties.setStorageRoot(tempDir.resolve("direct-transfer-oracle-storage"));
        properties.setFailureDomains(List.of("rack-a", "rack-b", "rack-c", "rack-d"));
        properties.getOracleObjectStorage().setNamespace("namespace");
        properties.getOracleObjectStorage().setBucket("bucket");
        properties.getOracleObjectStorage().setConfigFilePath("test-config-path");
        properties.getOracleObjectStorage().setConfigProfile("DEFAULT");

        return LocalClusterFactory.build(properties, bucketClient);
    }
}
