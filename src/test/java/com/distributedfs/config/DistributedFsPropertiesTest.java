package com.distributedfs.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DistributedFsPropertiesTest {

    @Test
    void validatesOracleBackendRequiresNamespaceBucketAndConfigFile() {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setStorageBackend(DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            properties::validateCrossFieldConstraints
        );

        assertEquals(
            "oracleObjectStorage.namespace must be configured for backend oracle-object-storage",
            error.getMessage()
        );
    }

    @Test
    void validatesOracleBackendWhenRequiredFieldsArePresent() {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.setStorageBackend(DistributedFsProperties.STORAGE_BACKEND_ORACLE_OBJECT_STORAGE);
        properties.getOracleObjectStorage().setNamespace("demo-namespace");
        properties.getOracleObjectStorage().setBucket("demo-bucket");
        properties.getOracleObjectStorage().setConfigFilePath("/tmp/oci-config");

        assertDoesNotThrow(properties::validateCrossFieldConstraints);
    }

    @Test
    void bootstrapAdminRequiresEmailAndPasswordTogether() {
        DistributedFsProperties properties = new DistributedFsProperties();
        properties.getBootstrapAdmin().setEmail("admin@example.com");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            properties::validateCrossFieldConstraints
        );

        assertEquals(
            "bootstrapAdmin.email and bootstrapAdmin.password must be configured together",
            error.getMessage()
        );
    }

    @Test
    void normalizesBootstrapAdminEmail() {
        DistributedFsProperties properties = new DistributedFsProperties();

        properties.getBootstrapAdmin().setEmail(" Admin@Example.com ");

        assertEquals("admin@example.com", properties.getBootstrapAdmin().getEmail());
    }
}
