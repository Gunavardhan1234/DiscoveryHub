package com.smarsh.discoveryhub.hold.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The FR-5 retention schedule (execution-plan Day 3 M4): a
 * {@code fixedDelayString} tick that runs a disposition pass through the same
 * {@link DispositionService} as the manual trigger. The service's advisory
 * lock guarantees only one replica executes a pass; a tick that loses the race
 * skips quietly. Never throws — a failed pass is recorded as a {@code FAILED}
 * ledger row, and the next tick tries again.
 */
@Component
public class DispositionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DispositionScheduler.class);

    private final DispositionService disposition;
    private final boolean enabled;

    public DispositionScheduler(DispositionService disposition,
                                @Value("${discoveryhub.disposition.scheduled-enabled:true}") boolean enabled) {
        this.disposition = disposition;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${discoveryhub.disposition.fixed-delay-ms:120000}",
            initialDelayString = "${discoveryhub.disposition.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            disposition.run(DispositionService.Trigger.SCHEDULED, "scheduler")
                    .ifPresent(run -> log.info("Scheduled disposition run {} started ({}); pass runs in the background",
                            run.id(), run.status()));
        } catch (Exception e) {
            log.warn("Scheduled disposition tick failed", e);
        }
    }
}
