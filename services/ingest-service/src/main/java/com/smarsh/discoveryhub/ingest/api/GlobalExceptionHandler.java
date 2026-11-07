package com.smarsh.discoveryhub.ingest.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.DomainException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * The single {@code @RestControllerAdvice} for ingest-service: maps domain
 * exceptions and bean-validation failures to the RFC-7807-shaped
 * {@link ApiError} envelope. Domain code never throws Spring/HTTP exceptions.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        log.error("Domain exception [{}]: {}", ex.code(), ex.getMessage(), ex);
        return ResponseEntity.status(ex.httpStatus())
                .body(api(ex.httpStatus(), ex.code(), ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage(),
                        fe.getRejectedValue() == null ? "" : String.valueOf(fe.getRejectedValue())))
                .toList();
        String detail = fieldErrors.stream()
                .map(fe -> fe.field() + ": " + fe.message())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(api(422, "VALIDATION_FAILED", detail, fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        var fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> new ApiError.FieldError(
                        cv.getPropertyPath().toString(),
                        cv.getMessage(),
                        cv.getInvalidValue() == null ? "" : String.valueOf(cv.getInvalidValue())))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(api(422, "VALIDATION_FAILED", "request failed validation", fieldErrors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(api(400, "BAD_REQUEST",
                        "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception [{}]: {}", CorrelationId.current(), ex.toString(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(api(500, "INTERNAL_ERROR", "Unexpected error: " + ex.getMessage(), null));
    }

    private static ApiError api(int status, String code, String detail, java.util.List<ApiError.FieldError> errors) {
        return ApiError.builder()
                .status(status)
                .code(code)
                .title(titleFor(status))
                .detail(detail)
                .correlationId(CorrelationId.current())
                .errors(errors)
                .build();
    }

    private static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }
}
