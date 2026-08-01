package com.distributedfs.api.dto;

import com.distributedfs.model.AuthenticatedSession;
import java.time.Instant;

public record AuthResponse(
    String token,
    Instant expiresAt,
    UserResponse user
) {

    public static AuthResponse fromSession(AuthenticatedSession session) {
        return new AuthResponse(
            session.token(),
            session.expiresAt(),
            UserResponse.fromUser(session.user())
        );
    }
}
