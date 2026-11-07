package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.domain.model.HeldItemException;
import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps archive domain exceptions to the RFC-7807 {@link ApiError} envelope
 * (same conventions as the other services' advice classes). The one archive
 * special case is {@link HeldItemException}: docs/api-contracts.md requires the
 * {@code 409 MESSAGE_ON_HOLD} body to carry the protecting hold ids —
 * {@code { "code": "MESSAGE_ON_HOLD", "holds": ["h-1"] }} — so investigators
 * can see exactly which holds block the delete (FR-4.6).
 */
@RestControllerAdvice
public class ArchiveExceptionHandler {

    /** {@link ApiError}-shaped body extended with the protecting hold ids. */
    public record MessageOnHoldError(
            String type, String title, int status, String detail, String code, List<String> holds) {
    }

    @ExceptionHandler(HeldItemException.class)
    public ResponseEntity<MessageOnHoldError> handleHeld(HeldItemException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageOnHoldError(
                        "about:blank",
                        "Conflict",
                        409,
                        ex.getMessage(),
                        "MESSAGE_ON_HOLD",
                        ex.holds().stream().sorted().toList()));
    }

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
