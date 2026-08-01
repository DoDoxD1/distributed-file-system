package com.distributedfs.util;

import com.distributedfs.error.AuthenticationException;
import com.distributedfs.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHashingUtil {

    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 65_536;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private PasswordHashingUtil() {
    }

    public static String hashPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new ValidationException("password must be non-empty");
        }

        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt);
        return ITERATIONS
            + ":"
            + Base64.getEncoder().encodeToString(salt)
            + ":"
            + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean matches(String password, String encodedHash) {
        if (password == null || encodedHash == null || encodedHash.isBlank()) {
            return false;
        }
        String[] parts = encodedHash.split(":");
        if (parts.length != 3) {
            throw new AuthenticationException("Stored password hash has invalid format");
        }
        int iterations = Integer.parseInt(parts[0]);
        byte[] salt = Base64.getDecoder().decode(parts[1]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[2]);
        byte[] actualHash = pbkdf2(password.toCharArray(), salt, iterations, expectedHash.length * 8);
        return constantTimeEquals(expectedHash, actualHash);
    }

    public static String hashToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ValidationException("token must be non-empty");
        }
        return HashingUtil.sha256Hex(token.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pbkdf2(char[] password, byte[] salt) {
        return pbkdf2(password, salt, ITERATIONS, KEY_LENGTH);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec keySpec = new PBEKeySpec(password, salt, iterations, keyLength);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing algorithm is unavailable", error);
        }
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int index = 0; index < left.length; index++) {
            diff |= left[index] ^ right[index];
        }
        return diff == 0;
    }
}
