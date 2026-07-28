package com.distributedfs.error;

/**
 * Raised when metadata state conflicts with requested operation.
 */
public class MetadataConflictException extends DistributedFsException {

    public MetadataConflictException(String message) {
        super(message);
    }
}
