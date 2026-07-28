package com.distributedfs.config;

import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.service.BackgroundWorkerService;
import com.distributedfs.service.GatewayService;
import com.distributedfs.service.MetadataService;
import com.distributedfs.service.StorageNode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates in-memory metadata, local storage nodes, and orchestrator services.
 */
@Configuration
@EnableConfigurationProperties(DistributedFsProperties.class)
public class ServiceConfiguration {

    private final DistributedFsProperties properties;

    public ServiceConfiguration(DistributedFsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateSettings() {
        properties.validateCrossFieldConstraints();
    }

    @Bean
    public MetadataService metadataService() {
        return new MetadataService();
    }

    @Bean
    public RackAwarePlacementStrategy placementStrategy() {
        return new RackAwarePlacementStrategy();
    }

    @Bean
    public Map<String, StorageNode> storageNodes() {
        List<StorageNode> nodes = new ArrayList<>();
        List<String> failureDomains = properties.getFailureDomains();

        Path storageRoot = properties.getStorageRoot();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException error) {
            throw new IllegalStateException(
                "Failed to create storage root directory: " + storageRoot,
                error
            );
        }

        for (int index = 0; index < properties.getNodeCount(); index++) {
            String nodeId = "node-" + (index + 1);
            String failureDomain = failureDomains.get(index % failureDomains.size());
            Path nodeDirectory = storageRoot.resolve(nodeId);
            nodes.add(new StorageNode(nodeId, failureDomain, nodeDirectory));
        }

        Map<String, StorageNode> nodeMap = new LinkedHashMap<>();
        for (StorageNode node : nodes) {
            nodeMap.put(node.nodeId(), node);
        }
        return nodeMap;
    }

    @Bean
    public GatewayService gatewayService(
        MetadataService metadataService,
        Map<String, StorageNode> storageNodes,
        RackAwarePlacementStrategy placementStrategy
    ) {
        return new GatewayService(metadataService, storageNodes, placementStrategy, properties);
    }

    @Bean
    public BackgroundWorkerService backgroundWorkerService(
        MetadataService metadataService,
        Map<String, StorageNode> storageNodes,
        RackAwarePlacementStrategy placementStrategy
    ) {
        return new BackgroundWorkerService(
            metadataService,
            storageNodes,
            placementStrategy,
            properties
        );
    }
}
