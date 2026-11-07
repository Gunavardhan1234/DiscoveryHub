package com.smarsh.discoveryhub.hold.domain.retention;

import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the retention {@link RetentionStrategy} resolution (FR-5,
 * execution-plan Day 1 M4). A configured policy yields the strategy's duration;
 * a missing type falls back to the default so disposition never accidentally
 * bulk-deletes an unconfigured type.
 */
class RetentionServiceTest {

    private final RetentionPolicyRepository policies = mock(RetentionPolicyRepository.class);
    private final RetentionService service = new RetentionService(policies, "PT168H");

    @Test
    void configuredTypeUsesPolicyDuration() {
        RetentionPolicyEntity email = new RetentionPolicyEntity();
        email.commType = "EMAIL";
        email.duration = "PT2M";
        when(policies.findById("EMAIL")).thenReturn(Optional.of(email));

        RetentionStrategy strategy = service.strategyFor("EMAIL");
        assertThat(strategy.commType()).isEqualTo("EMAIL");
        assertThat(strategy.retentionDuration()).isEqualTo(Duration.parse("PT2M"));
        assertThat(service.retentionFor("EMAIL")).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void unconfiguredTypeFallsBackToDefault() {
        when(policies.findById("CHAT")).thenReturn(Optional.empty());

        assertThat(service.retentionFor("CHAT")).isEqualTo(Duration.parse("PT168H"));
    }

    @Test
    void blankTypeFallsBackToDefault() {
        assertThat(service.retentionFor("")).isEqualTo(Duration.parse("PT168H"));
        assertThat(service.retentionFor(null)).isEqualTo(Duration.parse("PT168H"));
    }
}
