package com.distributedfs.error;

/**
 * Raised when no readable chunk replica is available.
 */
public class ReplicaUnavailableException extends DistributedFsException {

    public ReplicaUnavailableException(String message) {
        super(message);
    }
}
