package com.smarsh.discoveryhub.ingest.domain.model;

import com.smarsh.discoveryhub.common.DomainException;

/**
 * Signalled when publishing to Kafka fails. Maps to HTTP {@code 503} with code
 * {@code BROKER_UNAVAILABLE} (see {@code docs/api-contracts.md}). The use case
 * does not swallow this: a failed publish after a successful dedupe insert is
 * safe to retry because the dedupe row makes the retry a no-op.
 */
public class BrokerUnavailableException extends DomainException {

    public static final String CODE = "BROKER_UNAVAILABLE";

    public BrokerUnavailableException(String message, Throwable cause) {
        super(503, CODE, message, cause);
    }
}
