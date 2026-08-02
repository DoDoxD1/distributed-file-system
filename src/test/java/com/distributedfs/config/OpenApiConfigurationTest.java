package com.distributedfs.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAPI metadata configuration.
 */
class OpenApiConfigurationTest {

    @Test
    void distributedFsOpenApiReturnsExpectedMetadata() {
        OpenApiConfiguration configuration = new OpenApiConfiguration();

        OpenAPI openApi = configuration.distributedFsOpenApi();
        Info info = openApi.getInfo();

        assertNotNull(openApi);
        assertNotNull(info);
        assertEquals("Distributed File Storage API", info.getTitle());
        assertEquals("v1", info.getVersion());
        assertNotNull(info.getContact());
        assertEquals("Distributed File Storage Maintainer", info.getContact().getName());
        assertNotNull(info.getLicense());
        assertEquals("Internal/Personal Project", info.getLicense().getName());
        assertNotNull(openApi.getComponents());
        SecurityScheme bearerScheme = openApi.getComponents().getSecuritySchemes()
            .get(OpenApiConfiguration.BEARER_AUTH_SCHEME);
        assertNotNull(bearerScheme);
        assertEquals(SecurityScheme.Type.HTTP, bearerScheme.getType());
        assertEquals("bearer", bearerScheme.getScheme());
        assertEquals("opaque", bearerScheme.getBearerFormat());
    }
}
