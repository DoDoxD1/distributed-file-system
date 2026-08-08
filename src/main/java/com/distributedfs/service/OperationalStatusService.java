package com.distributedfs.service;

import com.distributedfs.error.ServiceUnavailableException;
import com.distributedfs.model.ApplicationVersionInfo;
import com.distributedfs.model.SystemHealth;
import com.distributedfs.util.TimeProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class OperationalStatusService {

    private static final String HEALTH_QUERY = "select 1";
    private static final String UP_STATUS = "UP";

    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final String applicationName;
    private final String applicationVersion;

    public OperationalStatusService(
        JdbcTemplate jdbcTemplate,
        TimeProvider timeProvider,
        String applicationName,
        String applicationVersion
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
    }

    public SystemHealth health() {
        Integer pingResult;
        try {
            pingResult = jdbcTemplate.queryForObject(HEALTH_QUERY, Integer.class);
        } catch (DataAccessException error) {
            throw new ServiceUnavailableException("Metadata database health check failed", error);
        }
        if (!Integer.valueOf(1).equals(pingResult)) {
            throw new ServiceUnavailableException(
                "Metadata database health check returned unexpected result: " + pingResult
            );
        }
        return new SystemHealth(UP_STATUS, UP_STATUS, timeProvider.now());
    }

    public ApplicationVersionInfo version() {
        return new ApplicationVersionInfo(applicationName, applicationVersion);
    }
}
