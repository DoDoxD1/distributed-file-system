package com.distributedfs.api;

import com.distributedfs.api.dto.AuthResponse;
import com.distributedfs.api.dto.CredentialsRequest;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.error.AuthenticationException;
import com.distributedfs.model.AuthenticatedSession;
import com.distributedfs.service.AuthenticationService;
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

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthResponse register(
        @Valid @RequestBody CredentialsRequest request,
        HttpServletResponse response
    ) {
        AuthenticatedSession session = authenticationService.register(
            request.email(),
            request.password()
        );
        writeRefreshCookie(response, session);
        return AuthResponse.fromSession(session);
    }

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

    private String requireRefreshTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            throw new AuthenticationException("Missing refresh token cookie");
        }
        for (Cookie cookie : cookies) {
            if (properties.getRefreshCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
                break;
            }
        }
        throw new AuthenticationException("Missing refresh token cookie");
    }
}
