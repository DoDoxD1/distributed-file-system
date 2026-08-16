package com.distributedfs.api;

import com.distributedfs.api.dto.AuthResponse;
import com.distributedfs.api.dto.CredentialsRequest;
import com.distributedfs.api.dto.RegistrationRequest;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.AuthenticationException;
import com.distributedfs.model.AuthenticatedSession;
import com.distributedfs.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final DistributedFsProperties properties;

    public AuthController(
        AuthenticationService authenticationService,
        DistributedFsProperties properties
    ) {
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    @Operation(
        summary = "Register a new user",
        description = "Creates a user account, returns a bearer access token, and sets a refresh-token cookie."
    )
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthResponse register(
        @Valid @RequestBody RegistrationRequest request,
        HttpServletResponse response
    ) {
        AuthenticatedSession session = authenticationService.register(
            request.email(),
            request.password(),
            request.displayName()
        );
        writeRefreshCookie(response, session);
        return AuthResponse.fromSession(session);
    }

    @Operation(
        summary = "Log in an existing user",
        description = "Authenticates the supplied credentials, returns a fresh bearer access token, and rotates the refresh-token cookie."
    )
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthResponse login(
        @Valid @RequestBody CredentialsRequest request,
        HttpServletResponse response
    ) {
        AuthenticatedSession session = authenticationService.login(
            request.email(),
            request.password()
        );
        writeRefreshCookie(response, session);
        return AuthResponse.fromSession(session);
    }

    @Operation(
        summary = "Refresh an access token",
        description = "Reads the refresh-token cookie, issues a new bearer access token, and rotates the refresh-token cookie."
    )
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthResponse refresh(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        AuthenticatedSession session = authenticationService.refresh(
            requireRefreshTokenCookie(request)
        );
        writeRefreshCookie(response, session);
        return AuthResponse.fromSession(session);
    }

    @Operation(
        summary = "Log out the current user session",
        description = "Revokes the current refresh session when present and clears the refresh-token cookie."
    )
    @PostMapping(value = "/logout")
    public void logout(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String refreshToken = findRefreshTokenCookieValue(request);
        if (refreshToken != null) {
            authenticationService.logout(refreshToken);
        }
        clearRefreshCookie(response);
    }

    private void writeRefreshCookie(
        HttpServletResponse response,
        AuthenticatedSession session
    ) {
        ResponseCookie cookie = ResponseCookie.from(
            properties.getRefreshCookieName(),
            session.refreshToken()
        )
            .httpOnly(true)
            .secure(properties.isRefreshCookieSecure())
            .path(properties.getRefreshCookiePath())
            .sameSite(properties.getRefreshCookieSameSite())
            .maxAge(properties.getRefreshTokenTtlSeconds())
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(
            properties.getRefreshCookieName(),
            ""
        )
            .httpOnly(true)
            .secure(properties.isRefreshCookieSecure())
            .path(properties.getRefreshCookiePath())
            .sameSite(properties.getRefreshCookieSameSite())
            .maxAge(0)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String requireRefreshTokenCookie(HttpServletRequest request) {
        String refreshToken = findRefreshTokenCookieValue(request);
        if (refreshToken != null) {
            return refreshToken;
        }
        throw new AuthenticationException("Missing refresh token cookie");
    }

    private String findRefreshTokenCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.getRefreshCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
                return null;
            }
        }
        return null;
    }
}
