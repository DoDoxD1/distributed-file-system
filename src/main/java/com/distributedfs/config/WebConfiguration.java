package com.distributedfs.config;

import com.distributedfs.api.AuthenticationInterceptor;
import com.distributedfs.api.WorkerAuthorizationInterceptor;
import com.distributedfs.service.AuthenticationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final AuthenticationService authenticationService;
    private final DistributedFsProperties properties;

    public WebConfiguration(
        AuthenticationService authenticationService,
        DistributedFsProperties properties
    ) {
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(properties.getCorsAllowedOriginPatterns().toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthenticationInterceptor(authenticationService))
            .addPathPatterns(
                "/api/v1/files",
                "/api/v1/files/**",
                "/api/v1/users",
                "/api/v1/users/**",
                "/api/v1/workers",
                "/api/v1/workers/**"
            );
        registry.addInterceptor(new WorkerAuthorizationInterceptor())
            .addPathPatterns(
                "/api/v1/workers",
                "/api/v1/workers/**"
            );
    }
}
