package com.distributedfs.error;

/**
 * Raised when a version ID is unknown for the requested logical path.
 */
public class VersionNotFoundException extends DistributedFsException {

    public VersionNotFoundException(String message) {
        super(message);
    }
}
