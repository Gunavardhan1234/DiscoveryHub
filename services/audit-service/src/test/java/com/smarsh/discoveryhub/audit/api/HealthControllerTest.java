package com.smarsh.discoveryhub.audit.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void alwaysReportsUp() {
        assertThat(new HealthController().health()).containsEntry("status", "UP");
    }
}
