package com.smarsh.discoveryhub.events.error;

/**
 * Base class for typed domain exceptions that surface through the
 * {@code @RestControllerAdvice} as RFC-7807 responses. Subclasses carry a
 * stable {@code code} (e.g. {@code INVALID_TRANSITION}, {@code CASE_CLOSED})
 * so clients can branch on it without parsing free-text detail.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    protected DomainException(int httpStatus, String code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    protected DomainException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
