package com.smarsh.discoveryhub.audit.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuditExceptionHandlerTest {

    private final AuditExceptionHandler handler = new AuditExceptionHandler();

    @Test
    void mapsIllegalArgumentToBadRequestWithInvalidAuditFilterCode() {
        ResponseEntity<?> response = handler.handleIllegalArg(new IllegalArgumentException("bad 'from' instant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = (com.smarsh.discoveryhub.common.ApiError) response.getBody();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.code()).isEqualTo("INVALID_AUDIT_FILTER");
        assertThat(body.detail()).isEqualTo("bad 'from' instant");
    }
}
