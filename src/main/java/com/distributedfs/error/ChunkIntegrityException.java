package com.distributedfs.error;

/**
 * Raised when checksum verification fails.
 */
public class ChunkIntegrityException extends DistributedFsException {

    public ChunkIntegrityException(String message) {
        super(message);
    }

    public ChunkIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
