package com.distributedfs.error;

/**
 * Raised when a logical path does not exist or has no active version.
 */
public class LogicalFileNotFoundException extends DistributedFsException {

    public LogicalFileNotFoundException(String message) {
        super(message);
    }
}
