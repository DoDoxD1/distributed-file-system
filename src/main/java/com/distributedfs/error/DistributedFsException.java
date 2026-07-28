package com.distributedfs.error;

/**
 * Base unchecked exception for distributed file storage domain errors.
 */
public class DistributedFsException extends RuntimeException {

    public DistributedFsException(String message) {
        super(message);
    }

    public DistributedFsException(String message, Throwable cause) {
        super(message, cause);
    }
}
