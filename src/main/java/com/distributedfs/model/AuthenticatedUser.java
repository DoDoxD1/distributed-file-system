package com.distributedfs.model;

import java.time.Instant;

public record AuthenticatedUser(
    String userId,
    String email,
    boolean isAdmin,
    Instant createdAt
) {
}
