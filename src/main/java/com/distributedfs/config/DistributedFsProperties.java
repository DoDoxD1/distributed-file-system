package com.distributedfs.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Runtime settings loaded from application configuration and environment overrides.
 */
@Validated
@ConfigurationProperties(prefix = "distributed.fs")
public class DistributedFsProperties {

    public static final String STORAGE_BACKEND_LOCAL = "local";
    public static final String STORAGE_BACKEND_ORACLE_OBJECT_STORAGE = "oracle-object-storage";

    @Min(1)
    private int chunkSizeBytes = 1_048_576;

    @Min(1)
    private int replicationFactor = 3;

    @Min(0)
    private int gcRetentionSeconds = 3_600;

    @Min(1)
    private int nodeCount = 4;

    @NotBlank
    private String storageBackend = STORAGE_BACKEND_LOCAL;

    @Valid
    private BootstrapAdminProperties bootstrapAdmin = new BootstrapAdminProperties();

    @Min(1)
    private long maxFileSizeBytes = 26_214_400;

    @Min(1)
    private long maxUserStorageBytes = 1_073_741_824;

    @Min(1)
    private long directUploadSessionTtlSeconds = 900;

    @Min(60)
    private long accessTokenTtlSeconds = 900;

    @Min(60)
    private long refreshTokenTtlSeconds = 86_400;

    @NotBlank
    private String refreshCookieName = "dfs_refresh_token";

    @NotBlank
    private String refreshCookiePath = "/api/v1/auth";

    private boolean refreshCookieSecure = true;

    @NotBlank
    private String refreshCookieSameSite = "Strict";

    @NotEmpty
    private List<@NotBlank String> corsAllowedOriginPatterns = FrontendUrlConstants.DEFAULT_CORS_ALLOWED_ORIGIN_PATTERNS;

    private Path storageRoot = Path.of(".dfs-storage");

    @Valid
    private OracleObjectStorageProperties oracleObjectStorage = new OracleObjectStorageProperties();

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

    public String getStorageBackend() {
        return storageBackend;
    }

    public void setStorageBackend(String storageBackend) {
        this.storageBackend = storageBackend == null
            ? null
            : storageBackend.strip().toLowerCase();
    }

    public BootstrapAdminProperties getBootstrapAdmin() {
        return bootstrapAdmin;
    }

    public void setBootstrapAdmin(BootstrapAdminProperties bootstrapAdmin) {
        this.bootstrapAdmin = bootstrapAdmin == null
            ? new BootstrapAdminProperties()
            : bootstrapAdmin;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public long getMaxUserStorageBytes() {
        return maxUserStorageBytes;
    }

    public void setMaxUserStorageBytes(long maxUserStorageBytes) {
        this.maxUserStorageBytes = maxUserStorageBytes;
    }

    public long getDirectUploadSessionTtlSeconds() {
        return directUploadSessionTtlSeconds;
    }

    public void setDirectUploadSessionTtlSeconds(long directUploadSessionTtlSeconds) {
        this.directUploadSessionTtlSeconds = directUploadSessionTtlSeconds;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }

    public String getRefreshCookiePath() {
        return refreshCookiePath;
    }

    public void setRefreshCookiePath(String refreshCookiePath) {
        this.refreshCookiePath = refreshCookiePath;
    }

    public boolean isRefreshCookieSecure() {
        return refreshCookieSecure;
    }

    public void setRefreshCookieSecure(boolean refreshCookieSecure) {
        this.refreshCookieSecure = refreshCookieSecure;
    }

    public String getRefreshCookieSameSite() {
        return refreshCookieSameSite;
    }

    public void setRefreshCookieSameSite(String refreshCookieSameSite) {
        this.refreshCookieSameSite = refreshCookieSameSite;
    }

    public List<String> getCorsAllowedOriginPatterns() {
        return corsAllowedOriginPatterns;
    }

    public void setCorsAllowedOriginPatterns(List<String> corsAllowedOriginPatterns) {
        if (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty()) {
            this.corsAllowedOriginPatterns = List.of();
            return;
        }
        this.corsAllowedOriginPatterns = corsAllowedOriginPatterns.stream()
            .map(this::normalizeCorsAllowedOriginPattern)
            .toList();
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public OracleObjectStorageProperties getOracleObjectStorage() {
        return oracleObjectStorage;
    }

    public void setOracleObjectStorage(OracleObjectStorageProperties oracleObjectStorage) {
        this.oracleObjectStorage = oracleObjectStorage;
    }

    public List<String> getFailureDomains() {
        return failureDomains;
    }

    public void setFailureDomains(List<String> failureDomains) {
        this.failureDomains = failureDomains;
    }

    public void validateCrossFieldConstraints() {
        String normalizedStorageBackend = storageBackend == null
            ? null
            : storageBackend.strip().toLowerCase();
        if (!STORAGE_BACKEND_LOCAL.equals(normalizedStorageBackend)
            && !STORAGE_BACKEND_ORACLE_OBJECT_STORAGE.equals(normalizedStorageBackend)) {
            throw new IllegalArgumentException(
                "storageBackend must be one of [" + STORAGE_BACKEND_LOCAL + ", "
                    + STORAGE_BACKEND_ORACLE_OBJECT_STORAGE + "]: " + storageBackend
            );
        }
        if (replicationFactor > nodeCount) {
            throw new IllegalArgumentException(
                "replicationFactor cannot exceed nodeCount: "
                    + replicationFactor + " > " + nodeCount
            );
        }
        if (storageRoot == null) {
            throw new IllegalArgumentException("storageRoot must be non-null");
        }
        if (maxFileSizeBytes > maxUserStorageBytes) {
            throw new IllegalArgumentException(
                "maxFileSizeBytes cannot exceed maxUserStorageBytes: "
                    + maxFileSizeBytes + " > " + maxUserStorageBytes
            );
        }
        if (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty()) {
            throw new IllegalArgumentException(
                "corsAllowedOriginPatterns must include at least one origin pattern"
            );
        }
        for (String corsAllowedOriginPattern : corsAllowedOriginPatterns) {
            if (corsAllowedOriginPattern == null || corsAllowedOriginPattern.isBlank()) {
                throw new IllegalArgumentException(
                    "corsAllowedOriginPatterns cannot contain blank values"
                );
            }
        }
        if (failureDomains == null || failureDomains.isEmpty()) {
            throw new IllegalArgumentException("failureDomains must include at least one domain");
        }
        for (String domain : failureDomains) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("failureDomains cannot contain blank values");
            }
        }
        boolean hasBootstrapAdminEmail = bootstrapAdmin != null
            && bootstrapAdmin.email != null
            && !bootstrapAdmin.email.isBlank();
        boolean hasBootstrapAdminPassword = bootstrapAdmin != null
            && bootstrapAdmin.password != null
            && !bootstrapAdmin.password.isBlank();
        if (hasBootstrapAdminEmail != hasBootstrapAdminPassword) {
            throw new IllegalArgumentException(
                "bootstrapAdmin.email and bootstrapAdmin.password must be configured together"
            );
        }
        if (STORAGE_BACKEND_ORACLE_OBJECT_STORAGE.equals(normalizedStorageBackend)) {
            OracleObjectStorageProperties oracleProperties = oracleObjectStorage;
            if (oracleProperties == null) {
                throw new IllegalArgumentException(
                    "oracleObjectStorage settings are required for backend "
                        + STORAGE_BACKEND_ORACLE_OBJECT_STORAGE
                );
            }
            if (oracleProperties.namespace == null || oracleProperties.namespace.isBlank()) {
                throw new IllegalArgumentException(
                    "oracleObjectStorage.namespace must be configured for backend "
                        + STORAGE_BACKEND_ORACLE_OBJECT_STORAGE
                );
            }
            if (oracleProperties.bucket == null || oracleProperties.bucket.isBlank()) {
                throw new IllegalArgumentException(
                    "oracleObjectStorage.bucket must be configured for backend "
                        + STORAGE_BACKEND_ORACLE_OBJECT_STORAGE
                );
            }
            if (oracleProperties.configFilePath == null || oracleProperties.configFilePath.isBlank()) {
                throw new IllegalArgumentException(
                    "oracleObjectStorage.configFilePath must be configured for backend "
                        + STORAGE_BACKEND_ORACLE_OBJECT_STORAGE
                );
            }
            if (oracleProperties.configProfile == null || oracleProperties.configProfile.isBlank()) {
                throw new IllegalArgumentException(
                    "oracleObjectStorage.configProfile must be non-blank when provided"
                );
            }
        }
    }

    private String normalizeCorsAllowedOriginPattern(String originPattern) {
        if (originPattern == null) {
            return "";
        }
        String normalizedOriginPattern = originPattern.strip();
        while (normalizedOriginPattern.length() > 1 && normalizedOriginPattern.endsWith("/")) {
            normalizedOriginPattern = normalizedOriginPattern.substring(
                0,
                normalizedOriginPattern.length() - 1
            );
        }
        return normalizedOriginPattern;
    }

    public static class BootstrapAdminProperties {

        private String email = "";

        private String password = "";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email == null
                ? ""
                : email.strip().toLowerCase(Locale.ROOT);
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }
    }

    public static class OracleObjectStorageProperties {

        private String namespace = "";

        private String bucket = "";

        private String objectPrefix = "distributed-fs";

        private String configFilePath = "";

        @NotBlank
        private String configProfile = "DEFAULT";

        @Min(1)
        private int connectionTimeoutMillis = 10_000;

        @Min(1)
        private int readTimeoutMillis = 30_000;

        @Min(0)
        private int maxRetries = 3;

        @Min(1)
        private long initialBackoffMillis = 500;

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getObjectPrefix() {
            return objectPrefix;
        }

        public void setObjectPrefix(String objectPrefix) {
            this.objectPrefix = objectPrefix;
        }

        public String getConfigFilePath() {
            return configFilePath;
        }

        public void setConfigFilePath(String configFilePath) {
            this.configFilePath = configFilePath;
        }

        public String getConfigProfile() {
            return configProfile;
        }

        public void setConfigProfile(String configProfile) {
            this.configProfile = configProfile;
        }

        public int getConnectionTimeoutMillis() {
            return connectionTimeoutMillis;
        }

        public void setConnectionTimeoutMillis(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getInitialBackoffMillis() {
            return initialBackoffMillis;
        }

        public void setInitialBackoffMillis(long initialBackoffMillis) {
            this.initialBackoffMillis = initialBackoffMillis;
        }
    }
}
