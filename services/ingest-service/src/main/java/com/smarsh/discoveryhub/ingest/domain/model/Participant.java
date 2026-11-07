package com.smarsh.discoveryhub.ingest.domain.model;

/**
 * A communication participant, in the domain's own vocabulary. The api layer
 * maps its DTO to this; the application layer feeds it to {@link MessageKey}.
 */
public record Participant(String name, String address, String role) {}
