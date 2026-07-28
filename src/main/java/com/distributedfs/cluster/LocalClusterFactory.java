package com.distributedfs.cluster;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.service.BackgroundWorkerService;
import com.distributedfs.service.GatewayService;
import com.distributedfs.service.MetadataService;
import com.distributedfs.service.StorageNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for constructing a complete local cluster without Spring context.
 */
public final class LocalClusterFactory {

    private LocalClusterFactory() {
    }

    /**
     * Builds all cluster services and nodes from properties.
     *
     * @param properties runtime settings
     * @return assembled local cluster
     */
    public static LocalCluster build(DistributedFsProperties properties) {
        properties.validateCrossFieldConstraints();
        Path storageRoot = properties.getStorageRoot();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException error) {
            throw new IllegalStateException(
                "Failed to create storage root directory: " + storageRoot,
                error
            );
        }

        List<StorageNode> nodeList = new ArrayList<>();
        for (int index = 0; index < properties.getNodeCount(); index++) {
            String nodeId = "node-" + (index + 1);
            String failureDomain = properties.getFailureDomains().get(
                index % properties.getFailureDomains().size()
            );
            Path nodeDirectory = storageRoot.resolve(nodeId);
            nodeList.add(new StorageNode(nodeId, failureDomain, nodeDirectory));
        }

        Map<String, StorageNode> nodeMap = new LinkedHashMap<>();
        for (StorageNode node : nodeList) {
            nodeMap.put(node.nodeId(), node);
        }

        MetadataService metadataService = new MetadataService();
        RackAwarePlacementStrategy placementStrategy = new RackAwarePlacementStrategy();
        GatewayService gatewayService = new GatewayService(
            metadataService,
            nodeMap,
            placementStrategy,
            properties
        );
        BackgroundWorkerService workerService = new BackgroundWorkerService(
            metadataService,
            nodeMap,
            placementStrategy,
            properties
        );

        return new LocalCluster(
            properties,
            metadataService,
            gatewayService,
            workerService,
            nodeMap
        );
    }
}
