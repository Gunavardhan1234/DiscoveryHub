package com.smarsh.discoveryhub.events.error;

/** 409 — a business rule was violated (state transition, closed case, held item). */
public class ConflictException extends DomainException {

    public ConflictException(String code, String detail) {
        super(409, code, detail);
    }
}
