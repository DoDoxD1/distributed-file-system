package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.distributedfs.cluster.LocalCluster;
import com.distributedfs.cluster.LocalClusterFactory;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.AuthenticationException;
import com.distributedfs.error.UserAlreadyExistsException;
import com.distributedfs.model.AuthenticatedSession;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void registerLoginAndAuthenticateRotateUserSession() {
        LocalCluster cluster = buildCluster();

        AuthenticatedSession registrationSession = cluster.authenticationService().register(
            "User@Example.com",
            "password123"
        );
        AuthenticatedUser registeredUser = registrationSession.user();

        assertEquals("user@example.com", registeredUser.email());
        assertEquals(900, registrationSession.accessTokenExpiresAt().getEpochSecond() - registrationSession.issuedAt().getEpochSecond());
        assertEquals(86_400, registrationSession.refreshTokenExpiresAt().getEpochSecond() - registrationSession.issuedAt().getEpochSecond());
        assertEquals(
            registeredUser,
            cluster.authenticationService().authenticate(registrationSession.accessToken())
        );

        AuthenticatedSession loginSession = cluster.authenticationService().login(
            "user@example.com",
            "password123"
        );

        assertEquals(registeredUser.userId(), loginSession.user().userId());
        assertNotEquals(registrationSession.accessToken(), loginSession.accessToken());
        assertNotEquals(registrationSession.refreshToken(), loginSession.refreshToken());
        assertEquals(
            loginSession.user(),
            cluster.authenticationService().authenticate(loginSession.accessToken())
        );
        assertThrows(
            AuthenticationException.class,
            () -> cluster.authenticationService().authenticate(registrationSession.accessToken())
        );
        assertThrows(
            AuthenticationException.class,
            () -> cluster.authenticationService().refresh(registrationSession.refreshToken())
        );
        assertThrows(
            AuthenticationException.class,
            () -> cluster.authenticationService().login("user@example.com", "wrongpass")
        );
        assertThrows(
            UserAlreadyExistsException.class,
            () -> cluster.authenticationService().register("user@example.com", "password123")
        );

        AuthenticatedSession refreshedSession = cluster.authenticationService().refresh(
            loginSession.refreshToken()
        );

        assertNotEquals(loginSession.accessToken(), refreshedSession.accessToken());
        assertNotEquals(loginSession.refreshToken(), refreshedSession.refreshToken());
        assertEquals(
            refreshedSession.user(),
            cluster.authenticationService().authenticate(refreshedSession.accessToken())
        );
        assertThrows(
            AuthenticationException.class,
            () -> cluster.authenticationService().refresh(loginSession.refreshToken())
        );
    }

    @Test
    void usersHaveIndependentNamespacesForSameLogicalPath() {
        LocalCluster cluster = buildCluster();

        AuthenticatedUser firstUser = cluster.authenticationService().register(
            "first@example.com",
            "password123"
        ).user();
        AuthenticatedUser secondUser = cluster.authenticationService().register(
            "second@example.com",
            "password123"
        ).user();

        FileManifest firstManifest = cluster.userFileService().uploadFile(
            firstUser,
            "/docs/report.txt",
            "first payload".getBytes(),
            null
        );
        FileManifest secondManifest = cluster.userFileService().uploadFile(
            secondUser,
            "/docs/report.txt",
            "second payload".getBytes(),
            null
        );

        assertEquals(firstUser.userId(), firstManifest.ownerUserId());
        assertEquals(secondUser.userId(), secondManifest.ownerUserId());
        assertEquals("/docs/report.txt", firstManifest.logicalPath());
        assertEquals("/docs/report.txt", secondManifest.logicalPath());
        assertNotEquals(firstManifest.fileId(), secondManifest.fileId());
        assertNotEquals(firstManifest.versionId(), secondManifest.versionId());

        assertArrayEquals(
            "first payload".getBytes(),
            cluster.userFileService().downloadFile(firstUser, "/docs/report.txt", null)
        );
        assertArrayEquals(
            "second payload".getBytes(),
            cluster.userFileService().downloadFile(secondUser, "/docs/report.txt", null)
        );

        List<FileListing> firstListings = cluster.userFileService().listFiles(firstUser, "/docs");
        List<FileListing> secondListings = cluster.userFileService().listFiles(secondUser, "/docs");

        assertEquals(List.of("/docs/report.txt"), firstListings.stream().map(FileListing::logicalPath).toList());
        assertEquals(List.of("/docs/report.txt"), secondListings.stream().map(FileListing::logicalPath).toList());

        List<FileManifest> firstVersions = cluster.userFileService().listVersions(firstUser, "/docs/report.txt");
        List<FileManifest> secondVersions = cluster.userFileService().listVersions(secondUser, "/docs/report.txt");

        assertEquals(List.of(firstManifest.versionId()), firstVersions.stream().map(FileManifest::versionId).toList());
        assertEquals(List.of(secondManifest.versionId()), secondVersions.stream().map(FileManifest::versionId).toList());
        assertTrue(firstVersions.stream().allMatch(manifest -> firstUser.userId().equals(manifest.ownerUserId())));
        assertTrue(secondVersions.stream().allMatch(manifest -> secondUser.userId().equals(manifest.ownerUserId())));
    }

    private LocalCluster buildCluster() {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setChunkSizeBytes(64);
        properties.setReplicationFactor(3);
        properties.setGcRetentionSeconds(0);
        properties.setNodeCount(4);
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(86_400);
        properties.setStorageRoot(tempDir.resolve("user-storage"));
        properties.setFailureDomains(List.of("rack-a", "rack-b", "rack-c", "rack-d"));

        return LocalClusterFactory.build(properties);
    }
}
