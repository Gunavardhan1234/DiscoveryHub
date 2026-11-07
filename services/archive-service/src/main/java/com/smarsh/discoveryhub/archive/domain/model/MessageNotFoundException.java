package com.smarsh.discoveryhub.archive.domain.model;

import com.smarsh.discoveryhub.common.DomainException;

/**
 * Thrown when a message id does not exist in the archive. Maps to
 * {@code 404 MESSAGE_NOT_FOUND} per docs/api-contracts.md §archive.
 */
public class MessageNotFoundException extends DomainException {

    public static final String CODE = "MESSAGE_NOT_FOUND";

    public MessageNotFoundException(String messageId) {
        super(404, CODE, "Message '" + messageId + "' not found");
    }
}
