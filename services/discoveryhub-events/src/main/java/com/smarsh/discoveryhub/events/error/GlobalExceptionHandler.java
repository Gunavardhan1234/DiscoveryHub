package com.smarsh.discoveryhub.events.error;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Shared {@code @RestControllerAdvice} producing the RFC-7807 {@link ApiError}
 * envelope for every service. Services may {@code @Import} or
 * {@code @ComponentScan} this class to pick it up; it is intentionally a
 * component so that a service without it gets default Spring error rendering.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler({NoSuchElementException.class, NotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex) {
        String code = ex instanceof NotFoundException nfe ? nfe.code() : "NOT_FOUND";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.builder()
                        .status(404)
                        .code(code)
                        .title("Not Found")
                        .detail(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.builder()
                        .status(409)
                        .code(ex.code())
                        .title("Conflict")
                        .detail(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .title("Bad Request")
                        .detail(detail)
                        .build());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .title("Bad Request")
                        .detail(detail)
                        .build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("BAD_REQUEST")
                        .title("Bad Request")
                        .detail("Invalid value for parameter '" + ex.getName() + "': " + ex.getValue())
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("BAD_REQUEST")
                        .title("Bad Request")
                        .detail(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.builder()
                        .status(500)
                        .code("INTERNAL_ERROR")
                        .title("Internal Server Error")
                        .detail("Unexpected error")
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
