package com.distributedfs.service;

import com.distributedfs.error.AuthenticationException;
import com.distributedfs.error.UserAlreadyExistsException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.AuthenticatedSession;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.util.PasswordHashingUtil;
import com.distributedfs.util.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class AuthenticationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TimeProvider timeProvider;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;

    public AuthenticationService(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        TimeProvider timeProvider,
        long accessTokenTtlSeconds,
        long refreshTokenTtlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.timeProvider = timeProvider;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public AuthenticatedSession register(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPassword = normalizePassword(password);
        String passwordHash = PasswordHashingUtil.hashPassword(normalizedPassword);
        Instant now = normalizeTimestamp(timeProvider.now());
        String userId = newId();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                    """
                    insert into dfs_users(user_id, email, password_hash, created_at)
                    values (?, ?, ?, ?)
                    """,
                    userId,
                    normalizedEmail,
                    passwordHash,
                    Timestamp.from(now)
                );
            });
        } catch (DuplicateKeyException error) {
            throw new UserAlreadyExistsException(
                "A user already exists with email: " + normalizedEmail
            );
        }

        UserRow persistedUser = requireUserByEmail(normalizedEmail);
        return createSession(persistedUser.userId(), persistedUser.email(), persistedUser.createdAt());
    }

    public AuthenticatedSession login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedPassword = normalizePassword(password);
        UserRow userRow = findUserByEmail(normalizedEmail);
        if (userRow == null || !PasswordHashingUtil.matches(normalizedPassword, userRow.passwordHash())) {
            throw new AuthenticationException("Invalid email or password");
        }
        return createSession(userRow.userId(), userRow.email(), userRow.createdAt());
    }

    public AuthenticatedUser authenticate(String token) {
        String normalizedToken = normalizeToken(token);
        String tokenHash = PasswordHashingUtil.hashToken(normalizedToken);
        AccessSessionRow sessionRow = jdbcTemplate.query(
            """
            select s.user_id, u.email, u.created_at, s.expires_at
            from dfs_user_sessions s
            join dfs_users u on u.user_id = s.user_id
            where s.token_hash = ?
            """,
            this::mapSessionRow,
            tokenHash
        ).stream().findFirst().orElse(null);
        if (sessionRow == null) {
            throw new AuthenticationException("Invalid authentication token");
        }
        Instant now = timeProvider.now();
        if (!sessionRow.expiresAt().isAfter(now)) {
            jdbcTemplate.update("delete from dfs_user_sessions where token_hash = ?", tokenHash);
            throw new AuthenticationException("Authentication token has expired");
        }
        return new AuthenticatedUser(sessionRow.userId(), sessionRow.email(), sessionRow.createdAt());
    }

    public AuthenticatedSession refresh(String refreshToken) {
        String normalizedRefreshToken = normalizeToken(refreshToken);
        String refreshTokenHash = PasswordHashingUtil.hashToken(normalizedRefreshToken);
        RefreshSessionRow refreshSession = jdbcTemplate.query(
            """
            select s.user_id, u.email, u.created_at, s.expires_at
            from dfs_user_refresh_sessions s
            join dfs_users u on u.user_id = s.user_id
            where s.token_hash = ?
            """,
            this::mapRefreshSessionRow,
            refreshTokenHash
        ).stream().findFirst().orElse(null);
        if (refreshSession == null) {
            throw new AuthenticationException("Invalid refresh token");
        }
        Instant now = timeProvider.now();
        if (!refreshSession.expiresAt().isAfter(now)) {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                    "delete from dfs_user_refresh_sessions where token_hash = ?",
                    refreshTokenHash
                );
                jdbcTemplate.update(
                    "delete from dfs_user_sessions where user_id = ?",
                    refreshSession.userId()
                );
            });
            throw new AuthenticationException("Refresh token has expired");
        }
        return createSession(
            refreshSession.userId(),
            refreshSession.email(),
            refreshSession.createdAt()
        );
    }

    private AuthenticatedSession createSession(String userId, String email, Instant userCreatedAt) {
        Instant now = normalizeTimestamp(timeProvider.now());
        Instant accessTokenExpiresAt = normalizeTimestamp(now.plusSeconds(accessTokenTtlSeconds));
        Instant refreshTokenExpiresAt = normalizeTimestamp(now.plusSeconds(refreshTokenTtlSeconds));
        String accessToken = generateToken();
        String refreshToken = generateToken();
        String accessTokenHash = PasswordHashingUtil.hashToken(accessToken);
        String refreshTokenHash = PasswordHashingUtil.hashToken(refreshToken);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                "delete from dfs_user_sessions where user_id = ?",
                userId
            );
            jdbcTemplate.update(
                "delete from dfs_user_refresh_sessions where user_id = ?",
                userId
            );
            jdbcTemplate.update(
                """
                insert into dfs_user_sessions(token_hash, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """,
                accessTokenHash,
                userId,
                Timestamp.from(now),
                Timestamp.from(accessTokenExpiresAt)
            );
            jdbcTemplate.update(
                """
                insert into dfs_user_refresh_sessions(token_hash, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """,
                refreshTokenHash,
                userId,
                Timestamp.from(now),
                Timestamp.from(refreshTokenExpiresAt)
            );
        });

        return new AuthenticatedSession(
            accessToken,
            accessTokenExpiresAt,
            refreshToken,
            refreshTokenExpiresAt,
            new AuthenticatedUser(userId, email, userCreatedAt),
            now
        );
    }

    private UserRow mapUserRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new UserRow(
            resultSet.getString("user_id"),
            resultSet.getString("email"),
            resultSet.getString("password_hash"),
            resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private UserRow findUserByEmail(String normalizedEmail) {
        return jdbcTemplate.query(
            """
            select user_id, email, password_hash, created_at
            from dfs_users
            where email = ?
            """,
            this::mapUserRow,
            normalizedEmail
        ).stream().findFirst().orElse(null);
    }

    private UserRow requireUserByEmail(String normalizedEmail) {
        UserRow userRow = findUserByEmail(normalizedEmail);
        if (userRow == null) {
            throw new AuthenticationException("Registered user could not be reloaded");
        }
        return userRow;
    }

    private AccessSessionRow mapSessionRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new AccessSessionRow(
            resultSet.getString("user_id"),
            resultSet.getString("email"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("expires_at").toInstant()
        );
    }

    private RefreshSessionRow mapRefreshSessionRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new RefreshSessionRow(
            resultSet.getString("user_id"),
            resultSet.getString("email"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("expires_at").toInstant()
        );
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            throw new ValidationException("email must be non-empty");
        }
        String normalized = email.strip().toLowerCase();
        if (normalized.isEmpty() || !normalized.contains("@")) {
            throw new ValidationException("email must be a valid address");
        }
        return normalized;
    }

    private static String normalizePassword(String password) {
        if (password == null) {
            throw new ValidationException("password must be non-empty");
        }
        String normalized = password.strip();
        if (normalized.length() < 8) {
            throw new ValidationException("password must be at least 8 characters");
        }
        return normalized;
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            throw new AuthenticationException("Missing authentication token");
        }
        String normalized = token.strip();
        if (normalized.isEmpty()) {
            throw new AuthenticationException("Missing authentication token");
        }
        return normalized;
    }

    private static String generateToken() {
        String randomValue = UUID.randomUUID() + ":" + UUID.randomUUID();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            randomValue.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Instant normalizeTimestamp(Instant timestamp) {
        return Timestamp.from(timestamp).toInstant();
    }

    private record UserRow(String userId, String email, String passwordHash, Instant createdAt) {
    }

    private record AccessSessionRow(String userId, String email, Instant createdAt, Instant expiresAt) {
    }

    private record RefreshSessionRow(String userId, String email, Instant createdAt, Instant expiresAt) {
    }
}
