package com.distributedfs.util;

import com.distributedfs.error.ValidationException;

public final class LogicalPathUtil {

    private static final String USER_NAMESPACE_ROOT = "/__users";

    private LogicalPathUtil() {
    }

    public static String toScopedPath(String userId, String logicalPath) {
        String namespaceRoot = namespaceRoot(userId);
        if (logicalPath == null) {
            throw new ValidationException("logicalPath must be non-empty");
        }
        String candidate = logicalPath.strip();
        if (candidate.isEmpty()) {
            throw new ValidationException("logicalPath must be non-empty");
        }
        if ("/".equals(candidate)) {
            return namespaceRoot;
        }
        return namespaceRoot + candidate;
    }

    public static String toScopedPrefix(String userId, String prefix) {
        String namespaceRoot = namespaceRoot(userId);
        if (prefix == null) {
            return namespaceRoot;
        }
        String candidate = prefix.strip();
        if (candidate.isEmpty() || "/".equals(candidate)) {
            return namespaceRoot;
        }
        return namespaceRoot + candidate;
    }

    public static String toPublicPath(String userId, String scopedPath) {
        String namespaceRoot = namespaceRoot(userId);
        if (!scopedPath.startsWith(namespaceRoot)) {
            throw new ValidationException("Scoped path does not belong to the authenticated user");
        }
        if (scopedPath.length() == namespaceRoot.length()) {
            return "/";
        }
        return scopedPath.substring(namespaceRoot.length());
    }

    private static String namespaceRoot(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ValidationException("userId must be non-empty");
        }
        return USER_NAMESPACE_ROOT + "/" + userId.strip();
    }
}
