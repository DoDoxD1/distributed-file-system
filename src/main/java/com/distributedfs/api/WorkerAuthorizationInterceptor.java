package com.distributedfs.api;

import com.distributedfs.error.AuthorizationException;
import com.distributedfs.model.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public final class WorkerAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(request);
        if (!user.isAdmin()) {
            throw new AuthorizationException(
                "Worker endpoints are restricted to the bootstrap admin user"
            );
        }
        return true;
    }
}
