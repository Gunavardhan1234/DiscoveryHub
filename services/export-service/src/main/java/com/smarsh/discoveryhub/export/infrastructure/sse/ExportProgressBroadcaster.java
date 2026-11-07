package com.smarsh.discoveryhub.export.infrastructure.sse;

import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.ExportProgressPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>Observer</b> (execution-plan M5 Day 3): the worker publishes job state
 * changes; this broadcaster fans them out to every SSE subscriber of that job
 * ({@code GET /api/exports/{id}/stream}). Terminal states complete the
 * emitters so browsers close their EventSource instead of reconnecting.
 * In-memory by design — progress is a live view, the source of truth stays in
 * Postgres and pollable via {@code GET /api/exports/{id}}.
 *
 * <p><b>SOLID — DIP + ISP:</b> implements the {@code ExportProgressPublisher}
 * <i>port</i>, so the worker publishes progress without knowing SSE exists —
 * the dependency arrow points from infrastructure into the domain, not the
 * other way round. The port is a single method: implementations can't be
 * forced to carry lifecycle baggage they don't need (ISP), and the worker's
 * tests substitute a no-op.
 */
@Component
public class ExportProgressBroadcaster implements ExportProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExportProgressBroadcaster.class);
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** Registers a subscriber and immediately replays the job's current state. */
    public SseEmitter subscribe(ExportJob current) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        UUID jobId = current.id();
        List<SseEmitter> jobEmitters = emitters.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>());
        jobEmitters.add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(e -> remove(jobId, emitter));
        send(jobId, emitter, current);
        if (isTerminal(current)) complete(jobId);
        return emitter;
    }

    @Override
    public void publish(ExportJob job) {
        List<SseEmitter> jobEmitters = emitters.get(job.id());
        if (jobEmitters != null) {
            for (SseEmitter emitter : jobEmitters) send(job.id(), emitter, job);
        }
        if (isTerminal(job)) complete(job.id());
    }

    private void send(UUID jobId, SseEmitter emitter, ExportJob job) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(toPayload(job), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE subscriber of job {} dropped: {}", jobId, e.getMessage());
            remove(jobId, emitter);
        }
    }

    private void complete(UUID jobId) {
        List<SseEmitter> jobEmitters = emitters.remove(jobId);
        if (jobEmitters == null) return;
        for (SseEmitter emitter : jobEmitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // subscriber already gone
            }
        }
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        List<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters != null) jobEmitters.remove(emitter);
    }

    private static boolean isTerminal(ExportJob job) {
        return job.status() == ExportJobStatus.COMPLETED || job.status() == ExportJobStatus.FAILED;
    }

    /** Same field names as {@code ExportResponse} so SSE and REST clients share a model. */
    private static Map<String, Object> toPayload(ExportJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.id().toString());
        payload.put("caseId", job.caseId());
        payload.put("holdId", job.holdId());
        payload.put("status", job.status().name());
        payload.put("progressPct", job.progressPct());
        payload.put("packageS3Key", job.packageS3Key());
        payload.put("error", job.error());
        return payload;
    }
}
