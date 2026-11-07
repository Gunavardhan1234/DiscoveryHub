package com.smarsh.discoveryhub.hold.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldCommand;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the hold-service transactional-outbox poller: publishes
 * pending rows to Kafka, stamps {@code publishedAt}, respects the batch size,
 * and never lets a bad row (unparsable JSON, broker error) block the rest of
 * the batch.
 */
class OutboxPollerTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    // HoldCommand.isPlace()/isRelease() look like bean getters to Jackson (a
    // phantom "place" property), which Spring Boot's autoconfigured
    // ObjectMapper tolerates (FAIL_ON_UNKNOWN_PROPERTIES off by default) but
    // a bare `new ObjectMapper()` does not — match production here.
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private OutboxPoller poller(int batchSize, boolean scheduledEnabled) {
        return new OutboxPoller(outbox, kafkaTemplate, mapper, batchSize, scheduledEnabled);
    }

    private OutboxEntity row(long id, String topic, String key, String payloadJson) {
        OutboxEntity row = new OutboxEntity();
        row.id = id;
        row.topic = topic;
        row.key = key;
        row.payload = payloadJson;
        return row;
    }

    private String commandJson(String holdId, String action) throws Exception {
        return mapper.writeValueAsString(new HoldCommand(holdId, "case-1", action, List.of("c-1"), null, null, null, "alice"));
    }

    @Test
    void pollPublishesEveryPendingRowAndStampsPublishedAt() throws Exception {
        OutboxEntity row1 = row(1L, "hold.commands.v1", "hold-1", commandJson("hold-1", HoldCommand.PLACE));
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(row1));

        poller(100, true).poll();

        verify(kafkaTemplate).send(eq("hold.commands.v1"), eq("hold-1"), any(EventEnvelope.class));
        assertThat(row1.publishedAt).isNotNull();
        verify(outbox).save(row1);
    }

    @Test
    void pollDoesNothingWhenNoPendingRows() {
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        poller(100, true).poll();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void pollHonoursTheBatchSizeLimit() throws Exception {
        OutboxEntity row1 = row(1L, "hold.commands.v1", "h1", commandJson("h1", HoldCommand.PLACE));
        OutboxEntity row2 = row(2L, "hold.commands.v1", "h2", commandJson("h2", HoldCommand.PLACE));
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(row1, row2));

        poller(1, true).poll();

        verify(kafkaTemplate).send(eq("hold.commands.v1"), eq("h1"), any(EventEnvelope.class));
        verify(kafkaTemplate, never()).send(eq("hold.commands.v1"), eq("h2"), any());
    }

    @Test
    void unparsablePayloadIsMarkedPublishedToAvoidAPoisonPill() {
        OutboxEntity row1 = row(1L, "hold.commands.v1", "h1", "not valid json");
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(row1));

        poller(100, true).poll();

        verify(kafkaTemplate, never()).send(any(), any(), any());
        assertThat(row1.publishedAt).as("marked published so a poison pill doesn't loop forever").isNotNull();
        verify(outbox).save(row1);
    }

    @Test
    void aBrokerErrorLeavesTheRowUnpublishedForTheNextCycle() throws Exception {
        OutboxEntity row1 = row(1L, "hold.commands.v1", "h1", commandJson("h1", HoldCommand.PLACE));
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(row1));
        when(kafkaTemplate.send(any(String.class), any(String.class), any())).thenThrow(new RuntimeException("kafka down"));

        poller(100, true).poll();

        assertThat(row1.publishedAt).as("not published — will retry next cycle").isNull();
        verify(outbox, never()).save(row1);
    }

    @Test
    void scheduledPollSkipsWhenDisabled() {
        poller(100, false).scheduledPoll();

        verify(outbox, never()).findByPublishedAtIsNullOrderByCreatedAtAsc();
    }

    @Test
    void scheduledPollDelegatesToPollWhenEnabled() {
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        poller(100, true).scheduledPoll();

        verify(outbox).findByPublishedAtIsNullOrderByCreatedAtAsc();
    }
}
