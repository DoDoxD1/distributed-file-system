package com.distributedfs.model;

import java.time.Instant;

public record SystemHealth(
    String status,
    String database,
    Instant checkedAt
) {
}
