package com.distributedfs.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Configures the metadata datasource from Spring properties while allowing JDBC URLs
 * to carry their own credentials.
 */
@Configuration
public class MetadataDataSourceConfiguration {

    /**
     * Creates the datasource used by Flyway and JDBC services.
     */
    @Bean(destroyMethod = "close")
    public DataSource dataSource(Environment environment) {
        HikariDataSource dataSource = new HikariDataSource();
        String jdbcUrl = requireProperty(environment, "spring.datasource.url");

        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setDriverClassName(
            environment.getProperty(
                "spring.datasource.driver-class-name",
                "org.postgresql.Driver"
            )
        );
        dataSource.setMaximumPoolSize(
            environment.getProperty(
                "spring.datasource.hikari.maximum-pool-size",
                Integer.class,
                8
            )
        );
        dataSource.setConnectionTimeout(
            environment.getProperty(
                "spring.datasource.hikari.connection-timeout",
                Long.class,
                30000L
            )
        );

        applyCredentials(
            dataSource,
            jdbcUrl,
            environment.getProperty("spring.datasource.username"),
            environment.getProperty("spring.datasource.password")
        );
        applyDataSourceProperty(
            dataSource,
            jdbcUrl,
            "sslmode",
            environment.getProperty(
                "spring.datasource.hikari.data-source-properties.sslmode"
            )
        );

        return dataSource;
    }

    private static void applyCredentials(
        HikariDataSource dataSource,
        String jdbcUrl,
        String username,
        String password
    ) {
        if (!hasQueryParameter(jdbcUrl, "user") && StringUtils.hasText(username)) {
            dataSource.setUsername(username);
        }
        if (!hasQueryParameter(jdbcUrl, "password") && StringUtils.hasText(password)) {
            dataSource.setPassword(password);
        }
    }

    private static void applyDataSourceProperty(
        HikariDataSource dataSource,
        String jdbcUrl,
        String key,
        String value
    ) {
        if (!hasQueryParameter(jdbcUrl, key) && StringUtils.hasText(value)) {
            dataSource.addDataSourceProperty(key, value);
        }
    }

    private static boolean hasQueryParameter(String jdbcUrl, String parameterName) {
        int queryStartIndex = jdbcUrl.indexOf('?');
        if (queryStartIndex < 0 || queryStartIndex == jdbcUrl.length() - 1) {
            return false;
        }

        String query = jdbcUrl.substring(queryStartIndex + 1);
        for (String part : query.split("&")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            int separatorIndex = part.indexOf('=');
            String candidateName = separatorIndex >= 0 ? part.substring(0, separatorIndex) : part;
            if (parameterName.equalsIgnoreCase(candidateName)) {
                return true;
            }
        }
        return false;
    }

    private static String requireProperty(Environment environment, String propertyName) {
        String value = environment.getProperty(propertyName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                "Missing required datasource property: " + propertyName
            );
        }
        return value;
    }
}
