package com.smarsh.discoveryhub.hold.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.DomainException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps {@code common} domain exceptions to the RFC-7807 {@link ApiError}
 * envelope for hold-service (same shape as case-service's advice). Without it,
 * {@code NotFoundException}/{@code ConflictException} would render as Spring's
 * default 500 — e.g. the disposition trigger's
 * {@code 409 DISPOSITION_ALREADY_RUNNING} and hold lookups' 404s.
 */
@RestControllerAdvice
public class HoldExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        return ResponseEntity.status(ex.httpStatus())
                .body(ApiError.builder()
                        .status(ex.httpStatus())
                        .code(ex.code())
                        .title(titleFor(ex.httpStatus()))
                        .detail(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest("VALIDATION_ERROR", detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest("VALIDATION_ERROR", detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex) {
        return badRequest("BAD_REQUEST", ex.getMessage());
    }

    private static ResponseEntity<ApiError> badRequest(String code, String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code(code)
                        .title("Bad Request")
                        .detail(detail)
                        .build());
    }

    private static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 500 -> "Internal Server Error";
            default -> "Error";
        };
    }
}
