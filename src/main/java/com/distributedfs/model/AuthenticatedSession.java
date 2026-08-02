package com.distributedfs.model;

import java.time.Instant;

public record AuthenticatedSession(
    String accessToken,
    Instant accessTokenExpiresAt,
    String refreshToken,
    Instant refreshTokenExpiresAt,
    AuthenticatedUser user,
    Instant issuedAt
) {
}
