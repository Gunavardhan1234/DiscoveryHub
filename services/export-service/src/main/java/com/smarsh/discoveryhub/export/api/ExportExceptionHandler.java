package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps export domain exceptions to the RFC-7807 {@link ApiError} envelope
 * (same conventions as the other services' advice classes).
 * {@link HttpMessageNotReadableException} covers the {@code ExportRequest}
 * compact-constructor guard — neither caseId nor holdId supplied — which
 * api-contracts.md pins to {@code 400 INVALID_EXPORT_REQUEST}.
 */
@RestControllerAdvice
public class ExportExceptionHandler {

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

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        String detail = ex instanceof HttpMessageNotReadableException wrapped && wrapped.getMostSpecificCause() != null
                ? wrapped.getMostSpecificCause().getMessage()
                : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.builder()
                        .status(400)
                        .code("INVALID_EXPORT_REQUEST")
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
