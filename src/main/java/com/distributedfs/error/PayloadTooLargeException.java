package com.distributedfs.error;

public class PayloadTooLargeException extends DistributedFsException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
