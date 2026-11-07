package com.smarsh.discoveryhub.hold.domain.retention;

import java.time.Duration;

/**
 * A {@link RetentionStrategy} backed by a configured ISO-8601 duration string
 * (loaded from {@code retention_policies}). Keeping the parse in the strategy
 * constructor lets the {@link RetentionService} surface invalid configuration
 * at the boundary rather than mid-disposition.
 */
public class ConfiguredRetentionStrategy implements RetentionStrategy {

    private final String commType;
    private final Duration duration;

    public ConfiguredRetentionStrategy(String commType, String isoDuration) {
        this.commType = commType;
        this.duration = Duration.parse(isoDuration);
    }

    @Override
    public String commType() {
        return commType;
    }

    @Override
    public Duration retentionDuration() {
        return duration;
    }
}
