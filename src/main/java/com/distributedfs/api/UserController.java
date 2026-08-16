package com.distributedfs.api;

import com.distributedfs.api.dto.UpdateDisplayNameRequest;
import com.distributedfs.api.dto.UserResponse;
import com.distributedfs.config.OpenApiConfiguration;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH_SCHEME)
public class UserController {

    private final AuthenticationService authenticationService;

    public UserController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Operation(
        summary = "Update display name",
        description = "Updates the display name for the authenticated user. Email cannot be changed."
    )
    @PatchMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse updateDisplayName(
        @Valid @RequestBody UpdateDisplayNameRequest request,
        HttpServletRequest httpRequest
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        AuthenticatedUser updated = authenticationService.updateDisplayName(
            user.userId(),
            request.displayName()
        );
        return UserResponse.fromUser(updated);
    }
}
