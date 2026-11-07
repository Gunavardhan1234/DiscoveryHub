package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.hold.domain.model.HoldStatus;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldRepository;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes {@code hold.applied.v1} in the {@code hold-tracker} group (events.md
 * §3). Each batch reports how many messages had the hold applied and the running
 * {@code progressPct}; when the archive hold-applier sends a batch with
 * {@code completed:true} this consumer flips the hold to {@code ACTIVE} (PLACE)
 * or {@code RELEASED} (RELEASE) and records the final {@code heldCount}.
 *
 * <p>Deserialization is manual (String + {@link TypeReference}) to match the
 * archive-service producer, which sends raw JSON without type headers — so the
 * consumer never depends on JVM type metadata in the message.
 */
@Component
public class HoldAppliedConsumer {

    private static final Logger log = LoggerFactory.getLogger(HoldAppliedConsumer.class);
    private static final TypeReference<EventEnvelope<HoldApplied>> TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final HoldRepository holds;

    public HoldAppliedConsumer(ObjectMapper mapper, HoldRepository holds) {
        this.mapper = mapper;
        this.holds = holds;
    }

    @KafkaListener(topics = Topics.HOLD_APPLIED_V1, groupId = "hold-tracker")
    @Transactional
    public void onApplied(String value) {
        EventEnvelope<HoldApplied> envelope;
        try {
            envelope = mapper.readValue(value, TYPE);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse hold.applied.v1 payload", e);
        }
        HoldApplied applied = envelope.payload();
        UUID holdId = parseId(applied.holdId());
        if (holdId == null) {
            log.warn("hold.applied.v1 with unparsable holdId {}; skipping", applied.holdId());
            return;
        }
        holds.findById(holdId).ifPresent(hold -> applyProgress(hold, applied));
    }

    private void applyProgress(HoldEntity hold, HoldApplied applied) {
        int batchSize = applied.messageIds() == null ? 0 : applied.messageIds().size();
        if (HoldApplied.RELEASE.equals(applied.action())) {
            hold.appliedCount = Math.max(0, hold.appliedCount - batchSize);
        } else {
            hold.appliedCount = hold.appliedCount + batchSize;
        }
        if (applied.completed()) {
            if (HoldApplied.RELEASE.equals(applied.action())) {
                hold.status = HoldStatus.RELEASED;
                if (hold.releasedAt == null) {
                    hold.releasedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
                }
            } else {
                hold.heldCount = hold.appliedCount;
                hold.status = HoldStatus.ACTIVE;
            }
            log.info("Hold {} -> {} (heldCount={}, appliedCount={})",
                    hold.id, hold.status, hold.heldCount, hold.appliedCount);
        } else {
            log.debug("Hold {} progress {}% (appliedCount={})",
                    hold.id, applied.progressPct(), hold.appliedCount);
        }
        holds.save(hold);
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
