package com.distributedfs.api;

import com.distributedfs.api.dto.ErrorResponse;
import com.distributedfs.error.AuthenticationException;
import com.distributedfs.error.AuthorizationException;
import com.distributedfs.error.ChunkNotFoundException;
import com.distributedfs.error.DistributedFsException;
import com.distributedfs.error.LogicalFileNotFoundException;
import com.distributedfs.error.PayloadTooLargeException;
import com.distributedfs.error.ServiceUnavailableException;
import com.distributedfs.error.StorageQuotaExceededException;
import com.distributedfs.error.UserAlreadyExistsException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.error.VersionDeletedException;
import com.distributedfs.error.VersionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain and validation exceptions to HTTP API responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({
        ValidationException.class,
        MethodArgumentNotValidException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleValidationException(
        Exception error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.BAD_REQUEST, "validation_error", error, request);
    }

    @ExceptionHandler({
        LogicalFileNotFoundException.class,
        ChunkNotFoundException.class,
        VersionNotFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleNotFoundException(
        DistributedFsException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.NOT_FOUND, "not_found", error, request);
    }

    @ExceptionHandler(VersionDeletedException.class)
    public ResponseEntity<ErrorResponse> handleDeletedVersionException(
        VersionDeletedException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.GONE, "version_deleted", error, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
        AuthenticationException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "authentication_error", error, request);
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationException(
        AuthorizationException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.FORBIDDEN, "authorization_error", error, request);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExistsException(
        UserAlreadyExistsException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.CONFLICT, "user_exists", error, request);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLargeException(
        PayloadTooLargeException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", error, request);
    }

    @ExceptionHandler(StorageQuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleStorageQuotaExceededException(
        StorageQuotaExceededException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.CONFLICT, "storage_quota_exceeded", error, request);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailableException(
        ServiceUnavailableException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", error, request);
    }

    @ExceptionHandler(DistributedFsException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(
        DistributedFsException error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.CONFLICT, "distributed_fs_error", error, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandledException(
        Exception error,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", error, request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(
        HttpStatus status,
        String errorCode,
        Exception error,
        HttpServletRequest request
    ) {
        ErrorResponse payload = new ErrorResponse(
            Instant.now(),
            errorCode,
            error.getMessage(),
            request.getRequestURI()
        );
        return ResponseEntity.status(status).body(payload);
    }
}
