package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.distributedfs.cluster.LocalCluster;
import com.distributedfs.cluster.LocalClusterFactory;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.DirectUploadSessionStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectTransferServiceTest {

    private static final String SAMPLE_SHA256 =
        "8f434346648f6b96df89dda901c5176b10a6d83961f9778f7f1449d84d35a32c";

    @TempDir
    Path tempDir;

    @Test
    void createUploadSessionPlansScopedStagingObjectWhenUploadIsRequired() {
        LocalCluster cluster = buildCluster();
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
        LocalCluster cluster = buildCluster();
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
}
