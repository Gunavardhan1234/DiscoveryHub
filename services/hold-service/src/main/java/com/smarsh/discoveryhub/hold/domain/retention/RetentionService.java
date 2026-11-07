package com.smarsh.discoveryhub.hold.domain.retention;

import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Context for the retention {@link RetentionStrategy} (FR-5). Given a
 * communication type, resolves the applicable strategy from the
 * {@code retention_policies} table; falls back to a configurable default so a
 * missing policy never triggers an accidental bulk delete. The disposition
 * executor (Day 3) calls {@link #retentionFor(String)} to decide eligibility.
 */
@Component
public class RetentionService {

    /** Default retention when no per-type policy is configured. */
    public static final Duration DEFAULT_RETENTION = Duration.parse("PT168H");

    private final RetentionPolicyRepository policies;
    private final Duration defaultRetention;

    public RetentionService(RetentionPolicyRepository policies,
                            @Value("${discoveryhub.retention.default-duration:PT168H}") String defaultDuration) {
        this.policies = policies;
        this.defaultRetention = Duration.parse(defaultDuration);
    }

    /** Returns the retention {@link RetentionStrategy} for a communication type. */
    public RetentionStrategy strategyFor(String commType) {
        return findByType(commType)
                .map(e -> (RetentionStrategy) new ConfiguredRetentionStrategy(e.commType, e.duration))
                .orElseGet(() -> new ConfiguredRetentionStrategy(commType, defaultRetention.toString()));
    }

    /** Convenience: the retention {@link Duration} for a communication type. */
    public Duration retentionFor(String commType) {
        return strategyFor(commType).retentionDuration();
    }

    private Optional<RetentionPolicyEntity> findByType(String commType) {
        if (commType == null || commType.isBlank()) {
            return Optional.empty();
        }
        return policies.findById(commType);
    }
}
