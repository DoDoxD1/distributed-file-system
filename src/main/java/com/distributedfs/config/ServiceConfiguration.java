package com.distributedfs.config;

import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.service.AuthenticationService;
import com.distributedfs.service.BackgroundWorkerService;
import com.distributedfs.service.DirectTransferService;
import com.distributedfs.service.GatewayService;
import com.distributedfs.service.LocalStorageNode;
import com.distributedfs.service.MetadataService;
import com.distributedfs.service.OciOracleObjectStorageBucketClient;
import com.distributedfs.service.OperationalStatusService;
import com.distributedfs.service.OracleObjectStorageBucketClient;
import com.distributedfs.service.OracleObjectStorageNode;
import com.distributedfs.service.StorageNode;
import com.distributedfs.service.UserFileService;
import com.distributedfs.service.UserStorageQuotaService;
import com.distributedfs.util.TimeProvider;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

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
    public MetadataService metadataService(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        TimeProvider timeProvider
    ) {
        return new MetadataService(jdbcTemplate, transactionManager, timeProvider);
    }

    @Bean
    public AuthenticationService authenticationService(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        TimeProvider timeProvider
    ) {
        return new AuthenticationService(
            jdbcTemplate,
            transactionManager,
            timeProvider,
            properties.getAccessTokenTtlSeconds(),
            properties.getRefreshTokenTtlSeconds()
        );
    }

    @Bean
    public ApplicationRunner bootstrapAdminRunner(AuthenticationService authenticationService) {
        return arguments -> authenticationService.ensureBootstrapAdmin(
            properties.getBootstrapAdmin().getEmail(),
            properties.getBootstrapAdmin().getPassword()
        );
    }

    @Bean
    public TimeProvider timeProvider() {
        return Instant::now;
    }

    @Bean
    public OperationalStatusService operationalStatusService(
        JdbcTemplate jdbcTemplate,
        TimeProvider timeProvider,
        @Value("${spring.application.name}") String applicationName,
        @Value("${spring.application.version:dev}") String applicationVersion
    ) {
        return new OperationalStatusService(
            jdbcTemplate,
            timeProvider,
            applicationName,
            applicationVersion
        );
    }

    @Bean
    public RackAwarePlacementStrategy placementStrategy() {
        return new RackAwarePlacementStrategy();
    }

    @Bean
    public Map<String, StorageNode> storageNodes(
        ObjectProvider<OracleObjectStorageBucketClient> bucketClientProvider
    ) {
        List<StorageNode> nodes = new ArrayList<>();
        List<String> failureDomains = properties.getFailureDomains();
        String storageBackend = normalizedStorageBackend();
        OracleObjectStorageBucketClient bucketClient = bucketClientProvider.getIfAvailable();

        Path storageRoot = properties.getStorageRoot();
        if (DistributedFsProperties.STORAGE_BACKEND_LOCAL.equals(storageBackend)) {
            try {
                Files.createDirectories(storageRoot);
            } catch (IOException error) {
                throw new IllegalStateException(
                    "Failed to create storage root directory: " + storageRoot,
                    error
                );
            }
        } else if (bucketClient == null) {
            throw new IllegalStateException(
                "Oracle Object Storage backend selected but no bucket client is available"
            );
        }

        for (int index = 0; index < properties.getNodeCount(); index++) {
            String nodeId = "node-" + (index + 1);
            String failureDomain = failureDomains.get(index % failureDomains.size());
            if (DistributedFsProperties.STORAGE_BACKEND_LOCAL.equals(storageBackend)) {
                Path nodeDirectory = storageRoot.resolve(nodeId);
                nodes.add(new LocalStorageNode(nodeId, failureDomain, nodeDirectory));
            } else {
                nodes.add(new OracleObjectStorageNode(
                    nodeId,
                    failureDomain,
                    bucketClient,
                    properties.getOracleObjectStorage().getObjectPrefix()
                ));
            }
        }

        Map<String, StorageNode> nodeMap = new LinkedHashMap<>();
        for (StorageNode node : nodes) {
            nodeMap.put(node.nodeId(), node);
        }
        return nodeMap;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
        prefix = "distributed.fs",
        name = "storage-backend",
        havingValue = DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE
    )
    public OracleObjectStorageBucketClient oracleObjectStorageBucketClient() {
        return new OciOracleObjectStorageBucketClient(properties.getOracleObjectStorage());
    }

    private String normalizedStorageBackend() {
        return properties.getStorageBackend().strip().toLowerCase();
    }

    @Bean
    public GatewayService gatewayService(
        MetadataService metadataService,
        Map<String, StorageNode> storageNodes,
        RackAwarePlacementStrategy placementStrategy,
        ObjectProvider<OracleObjectStorageBucketClient> bucketClientProvider
    ) {
        return new GatewayService(
            metadataService,
            storageNodes,
            placementStrategy,
            properties,
            bucketClientProvider.getIfAvailable()
        );
    }

    @Bean
    public UserStorageQuotaService userStorageQuotaService(
        GatewayService gatewayService,
        MetadataService metadataService,
        PlatformTransactionManager transactionManager
    ) {
        return new UserStorageQuotaService(
            gatewayService,
            metadataService,
            transactionManager,
            properties
        );
    }

    @Bean
    public DirectTransferService directTransferService(
        MetadataService metadataService,
        ObjectProvider<OracleObjectStorageBucketClient> bucketClientProvider,
        TimeProvider timeProvider
    ) {
        return new DirectTransferService(
            metadataService,
            properties,
            bucketClientProvider.getIfAvailable(),
            timeProvider
        );
    }

    @Bean
    public UserFileService userFileService(
        GatewayService gatewayService,
        UserStorageQuotaService userStorageQuotaService
    ) {
        return new UserFileService(gatewayService, userStorageQuotaService);
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
