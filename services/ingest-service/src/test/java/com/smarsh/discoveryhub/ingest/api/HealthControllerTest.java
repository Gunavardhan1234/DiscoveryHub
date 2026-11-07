package com.smarsh.discoveryhub.ingest.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void healthIsAlwaysUp() {
        assertThat(new HealthController().health()).containsEntry("status", "UP");
    }
}
