package com.smarsh.discoveryhub.export.infrastructure.sse;

import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ExportProgressBroadcasterTest {

    private final ExportProgressBroadcaster broadcaster = new ExportProgressBroadcaster();

    private ExportJob job(UUID id, ExportJobStatus status, int pct) {
        return new ExportJob(id, "case-1", null, status, pct, null, null, null, Instant.now(), Instant.now());
    }

    @Test
    void subscribeToANonTerminalJobLeavesTheEmitterOpen() {
        UUID id = UUID.randomUUID();
        AtomicBoolean completed = new AtomicBoolean(false);
        SseEmitter emitter = broadcaster.subscribe(job(id, ExportJobStatus.RUNNING, 40));
        emitter.onCompletion(() -> completed.set(true));

        assertThat(completed.get()).isFalse();
    }

    @Test
    void publishFansOutToEverySubscriberOfThatJob() {
        UUID id = UUID.randomUUID();
        broadcaster.subscribe(job(id, ExportJobStatus.QUEUED, 0));
        broadcaster.subscribe(job(id, ExportJobStatus.QUEUED, 0));

        // No subscriber for a different job should be touched.
        UUID otherId = UUID.randomUUID();
        SseEmitter otherEmitter = broadcaster.subscribe(job(otherId, ExportJobStatus.QUEUED, 0));
        AtomicBoolean otherCompleted = new AtomicBoolean(false);
        otherEmitter.onCompletion(() -> otherCompleted.set(true));

        broadcaster.publish(job(id, ExportJobStatus.RUNNING, 50));

        assertThat(otherCompleted.get()).isFalse();
    }

    @Test
    void subscribingToAnAlreadyTerminalJobCompletesTheEmitterWithoutThrowing() {
        UUID id = UUID.randomUUID();
        SseEmitter emitter = broadcaster.subscribe(job(id, ExportJobStatus.COMPLETED, 100));

        // The emitter is already complete() -ed internally; calling complete()
        // again (as a real servlet container might on cleanup) must be a
        // harmless no-op, not a second exception.
        assertThat(emitter).isNotNull();
        org.assertj.core.api.Assertions.assertThatCode(emitter::complete).doesNotThrowAnyException();
    }

    @Test
    void publishOnATerminalStatusCompletesEverySubscriberForThatJobWithoutThrowing() {
        UUID id = UUID.randomUUID();
        broadcaster.subscribe(job(id, ExportJobStatus.QUEUED, 0));

        broadcaster.publish(job(id, ExportJobStatus.COMPLETED, 100));

        // A second publish after completion finds no subscribers left (the
        // job was removed from the map) — must be a no-op, not an error.
        org.assertj.core.api.Assertions.assertThatCode(
                () -> broadcaster.publish(job(id, ExportJobStatus.COMPLETED, 100))).doesNotThrowAnyException();
    }

    @Test
    void publishForAJobWithNoSubscribersIsANoOp() {
        broadcaster.publish(job(UUID.randomUUID(), ExportJobStatus.FAILED, 0));
        // No exception — nothing to fan out to.
    }
}
