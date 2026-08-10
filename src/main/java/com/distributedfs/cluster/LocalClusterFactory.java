package com.distributedfs.cluster;

import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.placement.RackAwarePlacementStrategy;
import com.distributedfs.service.AuthenticationService;
import com.distributedfs.service.BackgroundWorkerService;
import com.distributedfs.service.DirectTransferService;
import com.distributedfs.service.GatewayService;
import com.distributedfs.service.LocalStorageNode;
import com.distributedfs.service.MetadataService;
import com.distributedfs.service.OciOracleObjectStorageBucketClient;
import com.distributedfs.service.OracleObjectStorageBucketClient;
import com.distributedfs.service.OracleObjectStorageNode;
import com.distributedfs.service.StorageNode;
import com.distributedfs.service.UserFileService;
import com.distributedfs.service.UserStorageQuotaService;
import com.distributedfs.util.TimeProvider;
import org.flywaydb.core.Flyway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
        String storageBackend = properties.getStorageBackend().strip().toLowerCase();
        OracleObjectStorageBucketClient bucketClient = null;
        if (DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE.equals(storageBackend)) {
            bucketClient = new OciOracleObjectStorageBucketClient(properties.getOracleObjectStorage());
        }
        for (int index = 0; index < properties.getNodeCount(); index++) {
            String nodeId = "node-" + (index + 1);
            String failureDomain = properties.getFailureDomains().get(
                index % properties.getFailureDomains().size()
            );
            if (DistributedFsProperties.STORAGE_BACKEND_LOCAL.equals(storageBackend)) {
                Path nodeDirectory = storageRoot.resolve(nodeId);
                nodeList.add(new LocalStorageNode(nodeId, failureDomain, nodeDirectory));
            } else {
                nodeList.add(new OracleObjectStorageNode(
                    nodeId,
                    failureDomain,
                    bucketClient,
                    properties.getOracleObjectStorage().getObjectPrefix()
                ));
            }
        }

        Map<String, StorageNode> nodeMap = new LinkedHashMap<>();
        for (StorageNode node : nodeList) {
            nodeMap.put(node.nodeId(), node);
        }

        DriverManagerDataSource metadataDataSource = new DriverManagerDataSource();
        metadataDataSource.setDriverClassName("org.h2.Driver");
        metadataDataSource.setUrl(buildMetadataDatabaseUrl(storageRoot));
        metadataDataSource.setUsername("sa");
        metadataDataSource.setPassword("");

        Flyway.configure()
            .dataSource(metadataDataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(metadataDataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            metadataDataSource
        );
        TimeProvider timeProvider = java.time.Instant::now;
        MetadataService metadataService = new MetadataService(
            jdbcTemplate,
            transactionManager,
            timeProvider
        );
        AuthenticationService authenticationService = new AuthenticationService(
            jdbcTemplate,
            transactionManager,
            timeProvider,
            properties.getAccessTokenTtlSeconds(),
            properties.getRefreshTokenTtlSeconds()
        );
        RackAwarePlacementStrategy placementStrategy = new RackAwarePlacementStrategy();
        GatewayService gatewayService = new GatewayService(
            metadataService,
            nodeMap,
            placementStrategy,
            properties
        );
        DirectTransferService directTransferService = new DirectTransferService(
            metadataService,
            properties,
            timeProvider
        );
        UserStorageQuotaService userStorageQuotaService = new UserStorageQuotaService(
            gatewayService,
            metadataService,
            transactionManager,
            properties
        );
        UserFileService userFileService = new UserFileService(
            gatewayService,
            userStorageQuotaService
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
            authenticationService,
            gatewayService,
            directTransferService,
            userFileService,
            workerService,
            nodeMap
        );
    }

    private static String buildMetadataDatabaseUrl(Path storageRoot) {
        String metadataPath = storageRoot.resolve("metadata-store").toAbsolutePath().toString()
            .replace('\\', '/');
        return "jdbc:h2:file:" + metadataPath
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
    }
}
