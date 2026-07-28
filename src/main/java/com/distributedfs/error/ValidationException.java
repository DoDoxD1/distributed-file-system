package com.distributedfs.error;

/**
 * Raised when request or boundary validation fails.
 */
public class ValidationException extends DistributedFsException {

    public ValidationException(String message) {
        super(message);
    }
}
