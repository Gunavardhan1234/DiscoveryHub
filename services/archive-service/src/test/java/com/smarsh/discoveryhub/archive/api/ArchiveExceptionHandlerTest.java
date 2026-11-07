package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.domain.model.HeldItemException;
import com.smarsh.discoveryhub.common.ApiError;
import com.smarsh.discoveryhub.common.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveExceptionHandlerTest {

    private final ArchiveExceptionHandler handler = new ArchiveExceptionHandler();

    @Test
    void heldItemExceptionMapsTo409WithSortedHoldIds() {
        ResponseEntity<ArchiveExceptionHandler.MessageOnHoldError> response =
                handler.handleHeld(new HeldItemException(Set.of("h-2", "h-1")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("MESSAGE_ON_HOLD");
        assertThat(response.getBody().holds()).containsExactly("h-1", "h-2");
    }

    @Test
    void domainExceptionMapsToItsOwnHttpStatusAndCode() {
        ResponseEntity<ApiError> response = handler.handleDomain(new NotFoundException("Message", "m-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().title()).isEqualTo("Not Found");
    }

    @Test
    void illegalArgumentMapsToBadRequest() {
        ResponseEntity<ApiError> response = handler.handleIllegalArg(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().detail()).isEqualTo("bad input");
    }
}
