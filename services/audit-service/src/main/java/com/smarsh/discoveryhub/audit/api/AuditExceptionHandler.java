package com.smarsh.discoveryhub.audit.api;

import com.smarsh.discoveryhub.common.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps bad filter input (e.g. a malformed {@code from}/{@code to} instant) to
 * the RFC-7807 {@link ApiError} envelope, same conventions as the other
 * services' advice classes.
 */
@RestControllerAdvice
public class AuditExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("INVALID_AUDIT_FILTER")
                        .title("Bad Request")
                        .detail(ex.getMessage())
                        .build());
    }
}
