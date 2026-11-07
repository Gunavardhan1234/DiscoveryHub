package com.smarsh.discoveryhub.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorTest {

    @AfterEach
    void clearThreadLocal() {
        CorrelationId.clear();
    }

    @Test
    void builderPopulatesRequiredFieldsAndDefaultsCorrelationId() {
        CorrelationId.set("c-123");
        ApiError error = ApiError.builder()
                .status(409)
                .code("MESSAGE_ON_HOLD")
                .title("Message is on hold")
                .detail("message msg-1 is protected by holds [h-1]")
                .build();

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("MESSAGE_ON_HOLD");
        assertThat(error.title()).isEqualTo("Message is on hold");
        assertThat(error.detail()).contains("msg-1");
        assertThat(error.correlationId()).isEqualTo("c-123");
        assertThat(error.timestamp()).isNotNull();
        assertThat(error.errors()).isEmpty();
    }

    @Test
    void generatesCorrelationIdWhenNoneSeeded() {
        ApiError error = ApiError.builder()
                .status(404)
                .code("NOT_FOUND")
                .title("Not found")
                .build();
        assertThat(error.correlationId()).isNotBlank();
    }

    @Test
    void domainExceptionCarriesStatusAndCode() {
        NotFoundException ex = new NotFoundException("case", "case-42");
        assertThat(ex.httpStatus()).isEqualTo(404);
        assertThat(ex.code()).isEqualTo("NOT_FOUND");
        assertThat(ex.getMessage()).contains("case-42");
    }
}
