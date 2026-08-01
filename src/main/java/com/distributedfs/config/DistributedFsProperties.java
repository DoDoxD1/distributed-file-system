package com.distributedfs.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Runtime settings loaded from application configuration and environment overrides.
 */
@Validated
@ConfigurationProperties(prefix = "distributed.fs")
public class DistributedFsProperties {

    @Min(1)
    private int chunkSizeBytes = 1_048_576;

    @Min(1)
    private int replicationFactor = 3;

    @Min(0)
    private int gcRetentionSeconds = 3_600;

    @Min(1)
    private int nodeCount = 4;

    @Min(60)
    private long sessionTtlSeconds = 604_800;

    private Path storageRoot = Path.of(".dfs-storage");

    @NotEmpty
    private List<@NotBlank String> failureDomains = List.of(
        "rack-a",
        "rack-b",
        "rack-c",
        "rack-d"
    );

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void setReplicationFactor(int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    public int getGcRetentionSeconds() {
        return gcRetentionSeconds;
    }

    public void setGcRetentionSeconds(int gcRetentionSeconds) {
        this.gcRetentionSeconds = gcRetentionSeconds;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public List<String> getFailureDomains() {
        return failureDomains;
    }

    public void setFailureDomains(List<String> failureDomains) {
        this.failureDomains = failureDomains;
    }

    public void validateCrossFieldConstraints() {
        if (replicationFactor > nodeCount) {
            throw new IllegalArgumentException(
                "replicationFactor cannot exceed nodeCount: "
                    + replicationFactor + " > " + nodeCount
            );
        }
        if (storageRoot == null) {
            throw new IllegalArgumentException("storageRoot must be non-null");
        }
        if (failureDomains == null || failureDomains.isEmpty()) {
            throw new IllegalArgumentException("failureDomains must include at least one domain");
        }
        for (String domain : failureDomains) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("failureDomains cannot contain blank values");
            }
        }
    }
}
