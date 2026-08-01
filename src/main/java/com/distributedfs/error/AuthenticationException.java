package com.distributedfs.error;

public class AuthenticationException extends DistributedFsException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
