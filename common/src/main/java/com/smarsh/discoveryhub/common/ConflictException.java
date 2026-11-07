package com.smarsh.discoveryhub.common;

/**
 * Thrown when an operation conflicts with the current state of an aggregate
 * — an illegal case transition, a delete on a held message, a duplicate
 * creation. Maps to HTTP {@code 409}.
 *
 * <p>Subclasses pin a specific business {@code code}
 * (e.g. {@code MESSAGE_ON_HOLD}, {@code INVALID_TRANSITION}) so callers can
 * branch on the stable code rather than parsing the message text.
 */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(409, code, message);
    }

    public ConflictException(String code, String message, Throwable cause) {
        super(409, code, message, cause);
    }
}
