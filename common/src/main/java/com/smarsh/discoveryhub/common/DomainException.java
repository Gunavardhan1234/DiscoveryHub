package com.smarsh.discoveryhub.common;

/**
 * Base class for all business/domain exceptions in DiscoveryHub.
 *
 * <p>Subclasses fix the HTTP {@link #httpStatus()} and the stable business
 * {@link #code()} (e.g. {@code MESSAGE_ON_HOLD}, {@code INVALID_TRANSITION}).
 * A global {@code @RestControllerAdvice} maps these to the {@link ApiError}
 * envelope; services should <strong>never</strong> throw raw
 * {@code RuntimeException} for a business rule violation — extend this class
 * so the error contract and audit trail stay consistent.
 */
public abstract class DomainException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    protected DomainException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    protected DomainException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
