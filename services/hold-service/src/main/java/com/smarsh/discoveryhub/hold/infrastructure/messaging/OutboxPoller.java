package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldCommand;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Polls the hold-service transactional outbox (D-008) and publishes unpublished
 * rows to Kafka. Each row's {@code payload} is the JSON of a {@link HoldCommand}
 * (written by {@code HoldService} in the same transaction as the hold row);
 * the poller wraps it in an {@link EventEnvelope} keyed by the hold id so all
 * commands for a hold land on one partition and are applied in order. After a
 * successful send it stamps {@code published_at} in a separate transaction so a
 * crash mid-publish can only produce an at-least-once duplicate — which the
 * archive hold-applier makes idempotent via {@code $addToSet}.
 *
 * <p>Publishing is intentionally separate from the hold write transaction: the
 * 202 to the caller means "durably recorded in Postgres", and this poller
 * forwards it to Kafka on its own cadence. This is the half of the outbox
 * pattern that decouples the write from broker availability.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final String EVENT_TYPE = "HOLD_COMMAND";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper mapper;
    private final int batchSize;
    private final boolean scheduledEnabled;

    public OutboxPoller(OutboxRepository outbox,
                        KafkaTemplate<String, Object> kafkaTemplate,
                        ObjectMapper mapper,
                        @Value("${discoveryhub.hold.outbox.batch-size:100}") int batchSize,
                        @Value("${discoveryhub.hold.outbox.scheduled-enabled:true}") boolean scheduledEnabled) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.batchSize = batchSize;
        this.scheduledEnabled = scheduledEnabled;
    }

    /**
     * Scheduled entry point — guards on {@code discoveryhub.hold.outbox.scheduled-enabled}
     * so integration tests can disable the automatic cadence and drive
     * {@link #poll()} manually for deterministic outbox assertions, without a
     * background tick racing ahead to publish (and stamp {@code published_at}
     * on) the row the test just wrote. Mirrors the
     * {@code discoveryhub.disposition.scheduled-enabled} guard on
     * {@code DispositionScheduler}.
     */
    @Scheduled(fixedDelayString = "${discoveryhub.hold.outbox.poll-interval-ms:2000}")
    public void scheduledPoll() {
        if (!scheduledEnabled) {
            return;
        }
        poll();
    }

    public void poll() {
        List<OutboxEntity> pending = outbox.findByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }
        int limit = Math.min(batchSize, pending.size());
        for (int i = 0; i < limit; i++) {
            publish(pending.get(i));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OutboxEntity row) {
        try {
            HoldCommand command = mapper.readValue(row.payload, HoldCommand.class);
            EventEnvelope<HoldCommand> envelope = new EventEnvelope<>(
                    UUID.randomUUID(),
                    EVENT_TYPE,
                    1,
                    Instant.now(),
                    command.holdId(),
                    CorrelationId.current(),
                    Actor.current(),
                    command);
            kafkaTemplate.send(row.topic, row.key, envelope);
            row.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
            outbox.save(row);
            log.debug("Published hold command {} action={} to {}", command.holdId(), command.action(), row.topic);
        } catch (JsonProcessingException e) {
            log.error("Unparsable outbox payload id={}; marking published to avoid a poison pill", row.id, e);
            row.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
            outbox.save(row);
        } catch (Exception e) {
            log.warn("Failed to publish outbox row id={}; will retry next cycle", row.id, e);
        }
    }
}
