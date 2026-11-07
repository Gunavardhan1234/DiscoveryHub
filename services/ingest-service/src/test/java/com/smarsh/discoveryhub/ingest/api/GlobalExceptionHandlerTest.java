package com.smarsh.discoveryhub.ingest.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.common.ValidationException;
import com.smarsh.discoveryhub.ingest.domain.model.BrokerUnavailableException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct unit tests for every {@code @ExceptionHandler} branch and the
 * {@code titleFor} status mapping — no Spring context needed.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void domainExceptionMapsToItsHttpStatusAndCode() {
        ResponseEntity<ApiError> response = handler.handleDomain(
                new ValidationException("body must not be blank"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().title()).isEqualTo("Unprocessable Entity");
        assertThat(response.getBody().detail()).isEqualTo("body must not be blank");
        assertThat(response.getBody().correlationId()).isNotBlank();
    }

    @Test
    void conflictMapsTo409() {
        ResponseEntity<ApiError> response = handler.handleDomain(
                new ConflictException("MESSAGE_ON_HOLD", "message is on hold"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("MESSAGE_ON_HOLD");
        assertThat(response.getBody().title()).isEqualTo("Conflict");
    }

    @Test
    void notFoundMapsTo404() {
        ResponseEntity<ApiError> response = handler.handleDomain(
                new NotFoundException("case", "case-42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().title()).isEqualTo("Not Found");
    }

    @Test
    void brokerUnavailableMapsTo503() {
        ResponseEntity<ApiError> response = handler.handleDomain(
                new BrokerUnavailableException("kafka down", new RuntimeException("cause")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(response.getBody().title()).isEqualTo("Service Unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void constraintViolationsMapTo422WithFieldErrors() {
        ConstraintViolation<String> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("ingest.body");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");
        when(violation.getInvalidValue()).thenReturn("");

        ResponseEntity<ApiError> response = handler.handleConstraint(
                new ConstraintViolationException(Set.of(violation)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().errors()).hasSize(1);
        assertThat(response.getBody().errors().get(0).field()).isEqualTo("ingest.body");
        assertThat(response.getBody().errors().get(0).message()).isEqualTo("must not be blank");
    }

    @Test
    void typeMismatchMapsTo400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "count", null, null);

        ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().title()).isEqualTo("Bad Request");
        assertThat(response.getBody().detail())
                .isEqualTo("Invalid value for parameter 'count': abc");
    }

    @Test
    void unexpectedExceptionMapsTo500() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().title()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().detail()).contains("boom");
    }
}
