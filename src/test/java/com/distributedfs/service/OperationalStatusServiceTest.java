package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.distributedfs.error.ServiceUnavailableException;
import com.distributedfs.model.ApplicationVersionInfo;
import com.distributedfs.model.SystemHealth;
import com.distributedfs.util.TimeProvider;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class OperationalStatusServiceTest {

    @Test
    void healthReturnsUpWhenDatabaseResponds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TimeProvider timeProvider = mock(TimeProvider.class);
        Instant checkedAt = Instant.parse("2026-08-03T06:30:00Z");
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(timeProvider.now()).thenReturn(checkedAt);

        OperationalStatusService service = new OperationalStatusService(
            jdbcTemplate,
            timeProvider,
            "distributed-file-storage-system",
            "0.1.0"
        );

        SystemHealth health = service.health();

        assertEquals("UP", health.status());
        assertEquals("UP", health.database());
        assertEquals(checkedAt, health.checkedAt());
    }

    @Test
    void healthThrowsServiceUnavailableWhenDatabasePingFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TimeProvider timeProvider = mock(TimeProvider.class);
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenThrow(
            new DataAccessResourceFailureException("db offline")
        );

        OperationalStatusService service = new OperationalStatusService(
            jdbcTemplate,
            timeProvider,
            "distributed-file-storage-system",
            "0.1.0"
        );

        ServiceUnavailableException error = assertThrows(
            ServiceUnavailableException.class,
            service::health
        );

        assertTrue(error.getMessage().contains("Metadata database health check failed"));
    }

    @Test
    void versionReturnsApplicationMetadata() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TimeProvider timeProvider = mock(TimeProvider.class);
        OperationalStatusService service = new OperationalStatusService(
            jdbcTemplate,
            timeProvider,
            "distributed-file-storage-system",
            "0.1.0"
        );

        ApplicationVersionInfo version = service.version();

        assertEquals("distributed-file-storage-system", version.application());
        assertEquals("0.1.0", version.version());
    }
}
