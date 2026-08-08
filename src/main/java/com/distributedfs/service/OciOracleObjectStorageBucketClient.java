package com.distributedfs.service;

import com.distributedfs.config.DistributedFsProperties.OracleObjectStorageProperties;
import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.error.DistributedFsException;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OciOracleObjectStorageBucketClient implements OracleObjectStorageBucketClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OciOracleObjectStorageBucketClient.class);
    private final ObjectStorageClient client;
    private final String namespace;
    private final String bucket;
    private final int maxRetries;
    private final long initialBackoffMillis;

    public OciOracleObjectStorageBucketClient(OracleObjectStorageProperties properties) {
        Objects.requireNonNull(properties, "properties must be non-null");
        this.namespace = properties.getNamespace().strip();
        this.bucket = properties.getBucket().strip();
        this.maxRetries = properties.getMaxRetries();
        this.initialBackoffMillis = properties.getInitialBackoffMillis();
        this.client = buildClient(properties);
    }

    @Override
    public boolean objectExists(String objectName) {
        return execute("head", objectName, () -> {
            try {
                client.headObject(HeadObjectRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(objectName)
                    .build());
                return true;
            } catch (BmcException error) {
                if (isNotFound(error)) {
                    return false;
                }
                throw error;
            }
        });
    }

    @Override
    public void putObject(String objectName, byte[] payload) {
        execute("put", objectName, () -> {
            client.putObject(PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(objectName)
                .contentLength((long) payload.length)
                .putObjectBody(new ByteArrayInputStream(payload))
                .build());
            return null;
        });
    }

    @Override
    public byte[] getObject(String objectName) {
        return execute("get", objectName, () -> {
            try (InputStream inputStream = client.getObject(GetObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(objectName)
                .build()).getInputStream()) {
                return inputStream.readAllBytes();
            } catch (BmcException error) {
                if (isNotFound(error)) {
                    throw new ChunkNotFoundException(
                        "Chunk object does not exist in Oracle Object Storage: " + objectName
                    );
                }
                throw error;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
    }

    @Override
    public void deleteObject(String objectName) {
        execute("delete", objectName, () -> {
            try {
                client.deleteObject(DeleteObjectRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(objectName)
                    .build());
            } catch (BmcException error) {
                if (!isNotFound(error)) {
                    throw error;
                }
            }
            return null;
        });
    }

    @Override
    public List<String> listObjectNames(String prefix) {
        return execute("list", prefix, () -> {
            List<String> objectNames = new ArrayList<>();
            String start = null;
            do {
                var response = client.listObjects(ListObjectsRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .prefix(prefix)
                    .start(start)
                    .build());
                objectNames.addAll(
                    response.getListObjects().getObjects().stream().map(ObjectSummary::getName).toList()
                );
                start = response.getListObjects().getNextStartWith();
            } while (start != null && !start.isBlank());
            return objectNames;
        });
    }

    @Override
    public void close() {
        client.close();
    }

    private ObjectStorageClient buildClient(OracleObjectStorageProperties properties) {
        try {
            ConfigFileReader.ConfigFile configFile = ConfigFileReader.parse(
                Path.of(properties.getConfigFilePath()).toString(),
                properties.getConfigProfile()
            );
            ConfigFileAuthenticationDetailsProvider provider =
                new ConfigFileAuthenticationDetailsProvider(configFile);
            ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectionTimeoutMillis(properties.getConnectionTimeoutMillis())
                .readTimeoutMillis(properties.getReadTimeoutMillis())
                .build();
            return new ObjectStorageClient(provider, clientConfiguration);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to initialize Oracle Object Storage client from config file",
                error
            );
        }
    }

    private <T> T execute(String operation, String target, Supplier<T> action) {
        long delayMillis = initialBackoffMillis;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (UncheckedIOException error) {
                throw new DistributedFsException(
                    "Oracle Object Storage " + operation + " failed for " + target,
                    error.getCause()
                );
            } catch (BmcException error) {
                if (!isRetryable(error) || attempt == maxRetries) {
                    throw new DistributedFsException(
                        "Oracle Object Storage " + operation + " failed for " + target
                            + " with status " + error.getStatusCode(),
                        error
                    );
                }
                LOGGER.warn(
                    "oracle object storage retry: operation={}, target={}, attempt={}, status={}",
                    operation,
                    target,
                    attempt + 1,
                    error.getStatusCode()
                );
                sleep(delayMillis);
                delayMillis = Math.max(delayMillis, 1L) * 2L;
            }
        }
        throw new DistributedFsException(
            "Oracle Object Storage " + operation + " failed for " + target
        );
    }

    private boolean isRetryable(BmcException error) {
        int statusCode = error.getStatusCode();
        return statusCode == 429 || statusCode >= 500 || statusCode == -1;
    }

    private boolean isNotFound(BmcException error) {
        return error.getStatusCode() == 404;
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DistributedFsException("Interrupted while retrying Oracle Object Storage call", error);
        }
    }
}
