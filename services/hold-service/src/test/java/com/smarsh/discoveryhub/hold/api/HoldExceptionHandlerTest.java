package com.smarsh.discoveryhub.hold.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HoldExceptionHandlerTest {

    private final HoldExceptionHandler handler = new HoldExceptionHandler();

    @Test
    void mapsNotFoundExceptionTo404() {
        ResponseEntity<ApiError> response = handler.handleDomain(new NotFoundException("Hold", "h-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().title()).isEqualTo("Not Found");
    }

    @Test
    void mapsConflictExceptionTo409WithItsCode() {
        ResponseEntity<ApiError> response = handler.handleDomain(
                new ConflictException("DISPOSITION_ALREADY_RUNNING", "a pass is already running"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("DISPOSITION_ALREADY_RUNNING");
        assertThat(response.getBody().title()).isEqualTo("Conflict");
        assertThat(response.getBody().detail()).isEqualTo("a pass is already running");
    }

    @Test
    void mapsValidationErrorsToBadRequestJoiningFieldMessages() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("holdRequest", "caseId", "must not be blank")));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiError> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().detail()).contains("caseId: must not be blank");
    }

    @Test
    void mapsConstraintViolationsToBadRequest() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("custodians");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be empty");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ApiError> response = handler.handleConstraint(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().detail()).contains("custodians: must not be empty");
    }

    @Test
    void mapsIllegalArgumentToBadRequest() {
        ResponseEntity<ApiError> response = handler.handleIllegalArg(new IllegalArgumentException("bad caseId"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().detail()).isEqualTo("bad caseId");
    }
}
