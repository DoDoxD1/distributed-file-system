package com.distributedfs.service;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.StorageQuotaExceededException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.FileManifest;
import com.distributedfs.util.LogicalPathUtil;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class UserStorageQuotaService {

    private final GatewayService gatewayService;
    private final MetadataService metadataService;
    private final DistributedFsProperties properties;
    private final TransactionTemplate transactionTemplate;

    public UserStorageQuotaService(
        GatewayService gatewayService,
        MetadataService metadataService,
        PlatformTransactionManager transactionManager,
        DistributedFsProperties properties
    ) {
        this.gatewayService = gatewayService;
        this.metadataService = metadataService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public FileManifest uploadFileWithinQuota(
        String userId,
        String logicalPath,
        byte[] payload,
        String idempotencyKey
    ) {
        if (payload == null) {
            throw new ValidationException("payload must be non-null");
        }
        return transactionTemplate.execute(status -> {
            metadataService.lockUserRow(userId);
            String scopedLogicalPath = LogicalPathUtil.toScopedPath(userId, logicalPath);
            if (idempotencyKey != null) {
                Optional<FileManifest> existingManifest = metadataService.findManifestByIdempotencyInDuplicateSeries(
                    scopedLogicalPath,
                    idempotencyKey
                );
                if (existingManifest.isPresent()) {
                    return existingManifest.get();
                }
            }

            long activeStorageBytes = metadataService.getActiveStorageBytesForUser(userId);
            long projectedStorageBytes = activeStorageBytes + payload.length;
            long maxUserStorageBytes = properties.getMaxUserStorageBytes();
            if (projectedStorageBytes > maxUserStorageBytes) {
                throw new StorageQuotaExceededException(
                    "Upload would exceed storage quota for user " + userId + ": "
                        + projectedStorageBytes + " > " + maxUserStorageBytes
                );
            }
            String resolvedLogicalPath = metadataService.resolveNextAvailableLogicalPath(
                userId,
                scopedLogicalPath
            );
            return gatewayService.uploadFile(resolvedLogicalPath, payload, idempotencyKey);
        });
    }
}
