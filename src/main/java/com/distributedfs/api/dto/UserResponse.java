package com.distributedfs.api.dto;

import com.distributedfs.model.AuthenticatedUser;
import java.time.Instant;

public record UserResponse(
    String userId,
    String email,
    String displayName,
    boolean isAdmin,
    Instant createdAt
) {

    public static UserResponse fromUser(AuthenticatedUser user) {
        return new UserResponse(user.userId(), user.email(), user.displayName(), user.isAdmin(), user.createdAt());
    }
}
