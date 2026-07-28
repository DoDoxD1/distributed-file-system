package com.distributedfs.error;

/**
 * Raised when replication durability threshold cannot be satisfied.
 */
public class DurabilityException extends DistributedFsException {

    public DurabilityException(String message) {
        super(message);
    }
}
