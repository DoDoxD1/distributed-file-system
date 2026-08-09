package com.distributedfs.config;

import com.distributedfs.api.AuthenticationInterceptor;
import com.distributedfs.api.WorkerAuthorizationInterceptor;
import com.distributedfs.service.AuthenticationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final AuthenticationService authenticationService;

    public WebConfiguration(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthenticationInterceptor(authenticationService))
            .addPathPatterns(
                "/api/v1/files",
                "/api/v1/files/**",
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
