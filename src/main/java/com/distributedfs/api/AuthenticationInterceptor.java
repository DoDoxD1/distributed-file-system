package com.distributedfs.api;

import com.distributedfs.error.AuthenticationException;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

public class AuthenticationInterceptor implements HandlerInterceptor {

    private final AuthenticationService authenticationService;

    public AuthenticationInterceptor(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthenticationException("Missing Bearer authentication token");
        }
        String token = authorizationHeader.substring("Bearer ".length()).strip();
        AuthenticatedUser user = authenticationService.authenticate(token);
        request.setAttribute(RequestUserContext.AUTHENTICATED_USER_ATTRIBUTE, user);
        return true;
    }
}
