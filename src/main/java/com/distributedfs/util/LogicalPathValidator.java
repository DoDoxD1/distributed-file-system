package com.distributedfs.util;

import com.distributedfs.error.ValidationException;

public final class LogicalPathValidator {

    private LogicalPathValidator() {
    }

    public static String normalizeLogicalPath(String logicalPath) {
        if (logicalPath == null) {
            throw new ValidationException("logicalPath must be non-empty");
        }

        String candidate = logicalPath.strip();
        if (candidate.isEmpty()) {
            throw new ValidationException("logicalPath must be non-empty");
        }
        if (!candidate.startsWith("/")) {
            throw new ValidationException("logicalPath must start with '/': " + logicalPath);
        }
        if (candidate.contains("//")) {
            throw new ValidationException(
                "logicalPath cannot contain '//' segments: " + logicalPath
            );
        }
        if (!candidate.equals("/") && candidate.endsWith("/")) {
            throw new ValidationException("logicalPath cannot end with '/': " + logicalPath);
        }
        return candidate;
    }

    public static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }

        String candidate = prefix.strip();
        if (candidate.isEmpty()) {
            return "";
        }
        if (!candidate.startsWith("/")) {
            throw new ValidationException("prefix must start with '/': " + prefix);
        }
        return candidate;
    }
}
