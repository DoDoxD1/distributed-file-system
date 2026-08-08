package com.distributedfs.api.dto;

import com.distributedfs.model.SystemHealth;
import java.time.Instant;

public record HealthResponse(
    String status,
    String database,
    Instant checkedAt
) {

    public static HealthResponse fromHealth(SystemHealth health) {
        return new HealthResponse(
            health.status(),
            health.database(),
            health.checkedAt()
        );
    }
}
