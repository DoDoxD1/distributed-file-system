package com.distributedfs.api;

import com.distributedfs.error.AuthenticationException;
import com.distributedfs.model.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;

public final class RequestUserContext {

    public static final String AUTHENTICATED_USER_ATTRIBUTE =
        RequestUserContext.class.getName() + ".AUTHENTICATED_USER";

    private RequestUserContext() {
    }

    public static AuthenticatedUser requireAuthenticatedUser(HttpServletRequest request) {
        Object attribute = request.getAttribute(AUTHENTICATED_USER_ATTRIBUTE);
        if (attribute instanceof AuthenticatedUser authenticatedUser) {
            return authenticatedUser;
        }
        throw new AuthenticationException("No authenticated user is available for this request");
    }
}
