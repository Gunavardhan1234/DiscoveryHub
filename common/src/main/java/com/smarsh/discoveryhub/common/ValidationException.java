package com.smarsh.discoveryhub.common;

import java.util.List;

/**
 * Thrown when a request fails business validation that bean validation
 * cannot express (cross-field rules, state-dependent checks). Maps to
 * HTTP {@code 422} with code {@code VALIDATION_FAILED}; field-level errors
 * are carried so the {@code @RestControllerAdvice} can populate
 * {@link ApiError.FieldError}.
 */
public class ValidationException extends DomainException {

    public static final String CODE = "VALIDATION_FAILED";

    private final List<ApiError.FieldError> fieldErrors;

    public ValidationException(String message) {
        super(422, CODE, message);
        this.fieldErrors = List.of();
    }

    public ValidationException(String message, List<ApiError.FieldError> fieldErrors) {
        super(422, CODE, message);
        this.fieldErrors = fieldErrors == null ? List.of() : fieldErrors;
    }

    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
