package com.distributedfs.error;

/**
 * Raised when an explicitly requested version is deleted.
 */
public class VersionDeletedException extends DistributedFsException {

    public VersionDeletedException(String message) {
        super(message);
    }
}
