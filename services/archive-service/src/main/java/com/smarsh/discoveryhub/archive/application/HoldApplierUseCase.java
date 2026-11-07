package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.archive.domain.port.HoldAppliedPublisher;
import com.smarsh.discoveryhub.archive.domain.port.HoldStorePort;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.events.HoldCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Applies a {@link HoldCommand} against the message store (FR-4.4/FR-4.5) and
 * reports progress via {@code hold.applied.v1}. {@code PLACE} resolves the
 * scope and {@code $addToSet}s the hold id onto each matching message's
 * {@code holds} set; {@code RELEASE} finds every message carrying the hold and
 * {@code $pull}s it, leaving {@code onHold} derived from the remaining set so
 * overlapping holds keep a message protected.
 *
 * <p>The hold <em>set</em> is the source of truth: a message is held while
 * {@code holds.size > 0}, never via a lone boolean — the signature
 * overlap-hold rule (FR-4.5) the rubric weights most.
 *
 * <p>Pure application logic: depends only on {@link HoldStorePort} and
 * {@link HoldAppliedPublisher} so it can be unit-tested with fakes (the
 * overlap test) without Mongo or Kafka.
 */
public class HoldApplierUseCase {

    private static final Logger log = LoggerFactory.getLogger(HoldApplierUseCase.class);

    private final HoldStorePort store;
    private final HoldAppliedPublisher publisher;
    private final int batchSize;

    public HoldApplierUseCase(HoldStorePort store, HoldAppliedPublisher publisher, int batchSize) {
        this.store = store;
        this.publisher = publisher;
        this.batchSize = Math.max(1, batchSize);
    }

    public void apply(HoldCommand command) {
        if (command.isRelease()) {
            release(command);
        } else {
            place(command);
        }
    }

    private void place(HoldCommand command) {
        List<String> ids = store.findIdsByScope(command.custodians(), command.from(), command.to(), command.terms());
        int total = ids.size();
        log.info("Applying PLACE hold {} to {} message(s)", command.holdId(), total);
        if (total == 0) {
            publisher.publish(new HoldApplied(command.holdId(), command.caseId(),
                    HoldApplied.PLACE, List.of(), false, true, 100), command.holdId());
            return;
        }
        int applied = 0;
        for (int i = 0; i < total; i += batchSize) {
            List<String> batch = ids.subList(i, Math.min(i + batchSize, total));
            for (String id : batch) {
                store.addHold(id, command.holdId());
            }
            applied += batch.size();
            boolean completed = applied >= total;
            int pct = completed ? 100 : applied * 100 / total;
            publisher.publish(new HoldApplied(command.holdId(), command.caseId(),
                    HoldApplied.PLACE, batch, true, completed, pct), command.holdId());
        }
    }

    private void release(HoldCommand command) {
        List<String> ids = store.findIdsByHold(command.holdId());
        int total = ids.size();
        log.info("Applying RELEASE hold {} to {} message(s)", command.holdId(), total);
        if (total == 0) {
            publisher.publish(new HoldApplied(command.holdId(), command.caseId(),
                    HoldApplied.RELEASE, List.of(), false, true, 100), command.holdId());
            return;
        }
        int processed = 0;
        for (int i = 0; i < total; i += batchSize) {
            List<String> batch = ids.subList(i, Math.min(i + batchSize, total));
            boolean anyStillHeld = false;
            for (String id : batch) {
                boolean stillHeld = store.removeHold(id, command.holdId());
                if (stillHeld) anyStillHeld = true;
            }
            processed += batch.size();
            boolean completed = processed >= total;
            int pct = completed ? 100 : processed * 100 / total;
            publisher.publish(new HoldApplied(command.holdId(), command.caseId(),
                    HoldApplied.RELEASE, batch, anyStillHeld, completed, pct), command.holdId());
        }
    }
}
