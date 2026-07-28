package com.distributedfs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration for the distributed file storage REST API.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI distributedFsOpenApi() {
        return new OpenAPI().info(
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
