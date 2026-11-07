package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.search.domain.SearchException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchExceptionHandlerTest {

    private final SearchExceptionHandler handler = new SearchExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void searchUnavailableMapsTo503() {
        ResponseEntity<ApiError> response = handler.searchUnavailable(
                new SearchException("ES down", new RuntimeException("timeout")), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("SEARCH_UNAVAILABLE");
    }

    @Test
    void malformedBodyMapsTo400() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);

        ResponseEntity<ApiError> response = handler.badBody(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void unexpectedExceptionMapsTo500() {
        ResponseEntity<ApiError> response = handler.unexpected(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
