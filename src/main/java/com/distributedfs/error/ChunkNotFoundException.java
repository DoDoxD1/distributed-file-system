package com.distributedfs.error;

/**
 * Raised when a chunk does not exist in metadata or node storage.
 */
public class ChunkNotFoundException extends DistributedFsException {

    public ChunkNotFoundException(String message) {
        super(message);
    }
}
