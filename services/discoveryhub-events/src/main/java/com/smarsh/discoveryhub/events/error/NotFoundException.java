package com.smarsh.discoveryhub.events.error;

/** 404 — entity not found. {@code code = NOT_FOUND}. */
public class NotFoundException extends DomainException {

    public NotFoundException(String resourceType, Object id) {
        super(404, "NOT_FOUND", resourceType + " '" + id + "' not found");
    }
}
