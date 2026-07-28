package com.distributedfs.util;

import com.distributedfs.error.ValidationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashing utilities for content checksums.
 */
public final class HashingUtil {

    private HashingUtil() {
    }

    public static String sha256Hex(byte[] payload) {
        if (payload == null) {
            throw new ValidationException("payload must be non-null");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", error);
        }
    }
}
