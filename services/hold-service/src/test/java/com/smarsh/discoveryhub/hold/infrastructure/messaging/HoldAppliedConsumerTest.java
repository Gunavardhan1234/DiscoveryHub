package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.hold.domain.model.HoldStatus;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.HoldRepository;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HoldAppliedConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HoldRepository holds = mock(HoldRepository.class);
    private final HoldAppliedConsumer consumer = new HoldAppliedConsumer(mapper, holds);

    private HoldEntity existingHold(UUID id, HoldStatus status, long heldCount, long appliedCount) {
        HoldEntity hold = new HoldEntity();
        hold.id = id;
        hold.status = status;
        hold.heldCount = heldCount;
        hold.appliedCount = appliedCount;
        return hold;
    }

    private String envelopeJson(HoldApplied applied) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), "HOLD_APPLIED", 1, java.time.Instant.now(), applied.holdId(),
                "corr-1", "system", applied));
    }

    @Test
    void placeBatchIncreasesAppliedCount() throws Exception {
        UUID holdId = UUID.randomUUID();
        HoldEntity hold = existingHold(holdId, HoldStatus.PENDING, 0, 5);
        when(holds.findById(holdId)).thenReturn(Optional.of(hold));
        HoldApplied applied = new HoldApplied(holdId.toString(), "case-1", HoldApplied.PLACE,
                List.of("m1", "m2"), true, false, 50);

        consumer.onApplied(envelopeJson(applied));

        assertThat(hold.appliedCount).isEqualTo(7);
        assertThat(hold.status).isEqualTo(HoldStatus.PENDING);
        verify(holds).save(hold);
    }

    @Test
    void completedPlaceBatchFlipsHoldToActiveAndSetsHeldCount() throws Exception {
        UUID holdId = UUID.randomUUID();
        HoldEntity hold = existingHold(holdId, HoldStatus.PENDING, 0, 3);
        when(holds.findById(holdId)).thenReturn(Optional.of(hold));
        HoldApplied applied = new HoldApplied(holdId.toString(), "case-1", HoldApplied.PLACE,
                List.of("m1"), true, true, 100);

        consumer.onApplied(envelopeJson(applied));

        assertThat(hold.status).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.appliedCount).isEqualTo(4);
        assertThat(hold.heldCount).isEqualTo(4);
    }

    @Test
    void releaseBatchDecreasesAppliedCount() throws Exception {
        UUID holdId = UUID.randomUUID();
        HoldEntity hold = existingHold(holdId, HoldStatus.RELEASING, 10, 10);
        when(holds.findById(holdId)).thenReturn(Optional.of(hold));
        HoldApplied applied = new HoldApplied(holdId.toString(), "case-1", HoldApplied.RELEASE,
                List.of("m1", "m2", "m3"), false, false, 30);

        consumer.onApplied(envelopeJson(applied));

        assertThat(hold.appliedCount).isEqualTo(7);
        assertThat(hold.status).isEqualTo(HoldStatus.RELEASING);
    }

    @Test
    void releaseBatchNeverGoesNegative() throws Exception {
        UUID holdId = UUID.randomUUID();
        HoldEntity hold = existingHold(holdId, HoldStatus.RELEASING, 2, 2);
        when(holds.findById(holdId)).thenReturn(Optional.of(hold));
        HoldApplied applied = new HoldApplied(holdId.toString(), "case-1", HoldApplied.RELEASE,
                List.of("m1", "m2", "m3", "m4"), false, false, 100);

        consumer.onApplied(envelopeJson(applied));

        assertThat(hold.appliedCount).isZero();
    }

    @Test
    void completedReleaseBatchFlipsHoldToReleasedAndStampsReleasedAt() throws Exception {
        UUID holdId = UUID.randomUUID();
        HoldEntity hold = existingHold(holdId, HoldStatus.RELEASING, 5, 5);
        when(holds.findById(holdId)).thenReturn(Optional.of(hold));
        HoldApplied applied = new HoldApplied(holdId.toString(), "case-1", HoldApplied.RELEASE,
                List.of("m1"), false, true, 100);

        consumer.onApplied(envelopeJson(applied));

        assertThat(hold.status).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.releasedAt).isNotNull();
    }

    @Test
    void unknownHoldIdIsIgnored() throws Exception {
        UUID holdId = UUID.randomUUID();
        when(holds.findById(holdId)).thenReturn(Optional.empty());
        HoldApplied applied = new HoldApplied(holdId.toString(), "case-1", HoldApplied.PLACE,
                List.of("m1"), true, true, 100);

        consumer.onApplied(envelopeJson(applied));

        verify(holds, never()).save(any());
    }

    @Test
    void unparsableHoldIdIsSkippedWithoutLookup() throws Exception {
        HoldApplied applied = new HoldApplied("not-a-uuid", "case-1", HoldApplied.PLACE,
                List.of("m1"), true, true, 100);

        consumer.onApplied(envelopeJson(applied));

        verify(holds, never()).findById(any());
    }

    @Test
    void malformedJsonThrowsSerializationException() {
        assertThatThrownBy(() -> consumer.onApplied("not json"))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("hold.applied.v1");
    }
}
