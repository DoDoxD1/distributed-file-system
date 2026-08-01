package com.distributedfs.service;

import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import com.distributedfs.util.LogicalPathUtil;
import java.util.List;

public class UserFileService {

    private final GatewayService gatewayService;

    public UserFileService(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    public FileManifest uploadFile(
        AuthenticatedUser user,
        String logicalPath,
        byte[] payload,
        String idempotencyKey
    ) {
        return toPublicManifest(
            user.userId(),
            gatewayService.uploadFile(
                LogicalPathUtil.toScopedPath(user.userId(), logicalPath),
                payload,
                idempotencyKey
            )
        );
    }

    public byte[] downloadFile(AuthenticatedUser user, String logicalPath, String versionId) {
        return gatewayService.downloadFile(
            LogicalPathUtil.toScopedPath(user.userId(), logicalPath),
            versionId
        );
    }

    public FileManifest getManifest(
        AuthenticatedUser user,
        String logicalPath,
        String versionId,
        boolean includeDeleted
    ) {
        return toPublicManifest(
            user.userId(),
            gatewayService.getManifest(
                LogicalPathUtil.toScopedPath(user.userId(), logicalPath),
                versionId,
                includeDeleted
            )
        );
    }

    public FileManifest deleteFile(AuthenticatedUser user, String logicalPath, String versionId) {
        return toPublicManifest(
            user.userId(),
            gatewayService.deleteFile(
                LogicalPathUtil.toScopedPath(user.userId(), logicalPath),
                versionId
            )
        );
    }

    public List<FileListing> listFiles(AuthenticatedUser user, String prefix) {
        return gatewayService.listFiles(
            LogicalPathUtil.toScopedPrefix(user.userId(), prefix)
        ).stream().map(listing -> toPublicListing(user.userId(), listing)).toList();
    }

    public List<FileManifest> listVersions(AuthenticatedUser user, String logicalPath) {
        return gatewayService.listVersions(
            LogicalPathUtil.toScopedPath(user.userId(), logicalPath)
        ).stream().map(manifest -> toPublicManifest(user.userId(), manifest)).toList();
    }

    private FileManifest toPublicManifest(String userId, FileManifest manifest) {
        return new FileManifest(
            manifest.fileId(),
            userId,
            LogicalPathUtil.toPublicPath(userId, manifest.logicalPath()),
            manifest.versionId(),
            manifest.chunkIds(),
            manifest.sizeBytes(),
            manifest.checksum(),
            manifest.createdAt(),
            manifest.idempotencyKey(),
            manifest.deletedAt()
        );
    }

    private FileListing toPublicListing(String userId, FileListing listing) {
        return new FileListing(
            LogicalPathUtil.toPublicPath(userId, listing.logicalPath()),
            listing.latestVersionId(),
            listing.sizeBytes(),
            listing.createdAt()
        );
    }
}
