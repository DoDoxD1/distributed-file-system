package com.distributedfs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration for the distributed file storage REST API.
 */
@Configuration
public class OpenApiConfiguration {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";
    private static final String WEBSITE_URL = "https://arihantjain.netlify.app/";
    private static final String LINKEDIN_PROFILE_URL = "http://linkedin.com/in/arihant-jain-software-engineer/";
    private static final String GITHUB_REPOSITORY_URL = "https://github.com/DoDoxD1/distributed-file-system";
    private static final String LEETCODE_PROFILE_URL = "https://leetcode.com/u/aunu/";

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
                    .title("Distributed File Storage API(Google Drive/ Onedrive Clone)")
                    .description(
                        "REST APIs for distributed file upload, download, versioning, and worker "
                            + "operations.\n\n"
                            + "[💼 LinkedIn](" + LINKEDIN_PROFILE_URL + ") | "
                            + "[💻 GitHub Repo](" + GITHUB_REPOSITORY_URL + ") | "
                            + "[🧠 LeetCode](" + LEETCODE_PROFILE_URL + ")"
                    )
                    .version("0.3")
                    .contact(
                        new Contact()
                            .name("Arihant Jain")
                            .url(WEBSITE_URL)
                    )
            );
    }
}
