package com.smarsh.discoveryhub.common;

import java.time.Instant;
import java.util.List;

/**
 * The single error envelope every service returns on a failure
 * (architecture &sect;6). RFC-7807-shaped so callers need exactly one
 * parser: {@code {type, title, status, detail, code, correlationId, timestamp, errors}}.
 *
 * <p>{@code code} is the stable, machine-readable business code
 * ({@code MESSAGE_ON_HOLD}, {@code INVALID_TRANSITION}, &hellip;);
 * {@code detail} is the human-readable explanation; {@code errors} carries
 * field-level validation failures when present.
 */
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String code,
        String correlationId,
        Instant timestamp,
        List<FieldError> errors) {

    public ApiError {
        if (timestamp == null) timestamp = Instant.now();
        if (errors == null) errors = List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public record FieldError(String field, String message, String rejectedValue) {
        public FieldError {
            if (rejectedValue == null) rejectedValue = "";
        }
    }

    public static final class Builder {
        private int status;
        private String code;
        private String title;
        private String type = "about:blank";
        private String detail = "";
        private String correlationId;
        private Instant timestamp;
        private List<FieldError> errors = List.of();

        private Builder() {
        }

        public Builder status(int status) { this.status = status; return this; }
        public Builder code(String code) { this.code = code; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder detail(String detail) { this.detail = detail; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder errors(List<FieldError> errors) { this.errors = errors; return this; }

        public ApiError build() {
            String cid = correlationId != null ? correlationId : CorrelationId.current();
            return new ApiError(type, title, status, detail, code, cid, timestamp, errors);
        }
    }
}
