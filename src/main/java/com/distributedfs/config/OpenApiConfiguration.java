package com.distributedfs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration for the distributed file storage REST API.
 */
@Configuration
public class OpenApiConfiguration {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI distributedFsOpenApi() {
        return new OpenAPI()
            .components(
                new Components().addSecuritySchemes(
                    BEARER_AUTH_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("opaque")
                )
            )
            .info(
                new Info()
                    .title("Distributed File Storage API")
                    .description(
                        "REST APIs for distributed file upload, download, versioning, and worker "
                            + "operations."
                    )
                    .version("v1")
                    .contact(
                        new Contact()
                            .name("Distributed File Storage Maintainer")
                    )
                    .license(new License().name("Internal/Personal Project"))
            );
    }
}
