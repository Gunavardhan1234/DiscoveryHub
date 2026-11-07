package com.smarsh.discoveryhub.export.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportExceptionHandlerTest {

    private final ExportExceptionHandler handler = new ExportExceptionHandler();

    @Test
    void mapsNotFoundTo404() {
        ResponseEntity<ApiError> response = handler.handleDomain(new NotFoundException("ExportJob", "j-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().title()).isEqualTo("Not Found");
    }

    @Test
    void mapsConflictTo409WithItsCode() {
        ResponseEntity<ApiError> response = handler.handleDomain(
                new ConflictException("EXPORT_NOT_READY", "not completed yet"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("EXPORT_NOT_READY");
        assertThat(response.getBody().title()).isEqualTo("Conflict");
    }

    @Test
    void mapsIllegalArgumentToBadRequest() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new IllegalArgumentException("Either caseId or holdId must be provided"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_EXPORT_REQUEST");
        assertThat(response.getBody().detail()).contains("caseId or holdId");
    }

    @Test
    void mapsMessageNotReadableUsingItsMostSpecificCause() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMostSpecificCause()).thenReturn(new IllegalArgumentException("Either caseId or holdId must be provided"));

        ResponseEntity<ApiError> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().detail()).contains("caseId or holdId");
    }
}
