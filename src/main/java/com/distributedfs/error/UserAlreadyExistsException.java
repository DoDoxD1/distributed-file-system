package com.distributedfs.error;

public class UserAlreadyExistsException extends DistributedFsException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
