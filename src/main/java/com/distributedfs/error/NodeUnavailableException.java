package com.distributedfs.error;

/**
 * Raised when a storage node is marked unhealthy.
 */
public class NodeUnavailableException extends DistributedFsException {

    public NodeUnavailableException(String message) {
        super(message);
    }
}
