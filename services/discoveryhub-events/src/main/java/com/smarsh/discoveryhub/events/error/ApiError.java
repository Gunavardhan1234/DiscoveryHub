package com.smarsh.discoveryhub.events.error;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC-7807 problem+json envelope shared by every service.
 * Shape: {@code {type, title, status, detail, code, correlationId, errors}}.
 */
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String code,
        String correlationId,
        List<FieldError> errors) {

    public ApiError {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public record FieldError(String field, String message, String rejectedValue) {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int status;
        private String code;
        private String title;
        private String type = "about:blank";
        private String detail;
        private String correlationId;
        private List<FieldError> errors = new ArrayList<>();

        private Builder() {
        }

        public Builder status(int status) { this.status = status; return this; }
        public Builder code(String code) { this.code = code; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder detail(String detail) { this.detail = detail; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder errors(List<FieldError> errors) { this.errors = errors; return this; }

        public ApiError build() {
            return new ApiError(type, title, status, detail, code, correlationId, errors);
        }
    }
}
