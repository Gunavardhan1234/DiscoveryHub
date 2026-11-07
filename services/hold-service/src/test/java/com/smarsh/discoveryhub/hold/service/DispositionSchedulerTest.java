package com.smarsh.discoveryhub.hold.service;

import com.smarsh.discoveryhub.hold.api.dto.DispositionRunResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The scheduled disposition tick: gated on the enabled flag, never propagates an exception. */
class DispositionSchedulerTest {

    private final DispositionService disposition = mock(DispositionService.class);

    private DispositionRunResponse sampleRun() {
        return new DispositionRunResponse("run-1", Instant.now(), null, "RUNNING", 0, 0, 0, 0, Map.of());
    }

    @Test
    void tickSkipsWhenDisabled() {
        new DispositionScheduler(disposition, false).tick();

        verify(disposition, never()).run(any(), any());
    }

    @Test
    void tickRunsWhenEnabled() {
        when(disposition.run(eq(DispositionService.Trigger.SCHEDULED), eq("scheduler")))
                .thenReturn(Optional.of(sampleRun()));

        new DispositionScheduler(disposition, true).tick();

        verify(disposition).run(DispositionService.Trigger.SCHEDULED, "scheduler");
    }

    @Test
    void tickHandlesNoRunStarted() {
        when(disposition.run(any(), any())).thenReturn(Optional.empty());

        new DispositionScheduler(disposition, true).tick();

        verify(disposition).run(DispositionService.Trigger.SCHEDULED, "scheduler");
    }

    @Test
    void tickNeverPropagatesAnExceptionFromTheService() {
        when(disposition.run(any(), any())).thenThrow(new RuntimeException("db down"));

        new DispositionScheduler(disposition, true).tick();
        // no assertion needed beyond "doesn't throw" — the scheduler must
        // never let a failed pass kill the @Scheduled thread.
    }
}
