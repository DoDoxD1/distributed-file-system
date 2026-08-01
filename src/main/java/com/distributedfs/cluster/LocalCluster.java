package com.distributedfs.cluster;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.service.AuthenticationService;
import com.distributedfs.service.BackgroundWorkerService;
import com.distributedfs.service.GatewayService;
import com.distributedfs.service.MetadataService;
import com.distributedfs.service.StorageNode;
import com.distributedfs.service.UserFileService;
import java.util.Map;

/**
 * Assembled in-process cluster services for local runs and tests.
 */
public record LocalCluster(
    DistributedFsProperties properties,
    MetadataService metadataService,
    AuthenticationService authenticationService,
    GatewayService gatewayService,
    UserFileService userFileService,
    BackgroundWorkerService backgroundWorkerService,
    Map<String, StorageNode> storageNodes
) {
}
