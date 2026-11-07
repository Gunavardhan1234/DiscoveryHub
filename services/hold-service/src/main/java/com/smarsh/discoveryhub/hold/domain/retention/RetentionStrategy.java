package com.smarsh.discoveryhub.hold.domain.retention;

import java.time.Duration;

/**
 * Strategy interface for per-communication-type retention (FR-5, execution-plan
 * Day 1 M4). Each communication type ({@code EMAIL}, {@code CHAT}, &hellip;) has
 * its own retention duration; the disposition executor asks the
 * {@link RetentionService} for the strategy for a message's type and deletes
 * only messages whose {@code ingestedAt + retentionDuration < now} and which
 * are not on hold. Demo values are minute-level so a retention run can be
 * observed live.
 *
 * <p>This is the {@code Strategy} pattern: the algorithm (the retention
 * duration) varies by the message type, selected at runtime by the context.
 */
public interface RetentionStrategy {

    /** The communication type this strategy applies to (e.g. {@code "EMAIL"}). */
    String commType();

    /** How long messages of this type are retained before disposition. */
    Duration retentionDuration();
}
