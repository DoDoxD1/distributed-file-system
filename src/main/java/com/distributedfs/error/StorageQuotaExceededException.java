package com.distributedfs.error;

public class StorageQuotaExceededException extends DistributedFsException {

    public StorageQuotaExceededException(String message) {
        super(message);
    }
}
