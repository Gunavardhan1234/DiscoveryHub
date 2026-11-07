package com.smarsh.discoveryhub.common;

/**
 * Thrown when a referenced aggregate (case, hold, message, job, &hellip;)
 * does not exist. Maps to HTTP {@code 404} with code {@code NOT_FOUND}.
 */
public class NotFoundException extends DomainException {

    public static final String CODE = "NOT_FOUND";

    public NotFoundException(String entityType, String id) {
        super(404, CODE, entityType + " '" + id + "' not found");
    }

    public NotFoundException(String message) {
        super(404, CODE, message);
    }
}
