package com.distributedfs.api;

import com.distributedfs.api.dto.AuthResponse;
import com.distributedfs.api.dto.CredentialsRequest;
import com.distributedfs.model.AuthenticatedSession;
import com.distributedfs.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthResponse register(@Valid @RequestBody CredentialsRequest request) {
        AuthenticatedSession session = authenticationService.register(
            request.email(),
            request.password()
        );
        return AuthResponse.fromSession(session);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthResponse login(@Valid @RequestBody CredentialsRequest request) {
        AuthenticatedSession session = authenticationService.login(
            request.email(),
            request.password()
        );
        return AuthResponse.fromSession(session);
    }
}
