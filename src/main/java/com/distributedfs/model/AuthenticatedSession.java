package com.distributedfs.model;

import java.time.Instant;

public record AuthenticatedSession(
    String token,
    AuthenticatedUser user,
    Instant expiresAt
) {
}
