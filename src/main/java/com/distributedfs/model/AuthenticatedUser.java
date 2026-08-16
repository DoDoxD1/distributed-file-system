package com.distributedfs.model;

import java.time.Instant;

public record AuthenticatedUser(
    String userId,
    String email,
    String displayName,
    boolean isAdmin,
    Instant createdAt
) {
}
