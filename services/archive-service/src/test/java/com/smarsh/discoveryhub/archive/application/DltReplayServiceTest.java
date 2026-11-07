package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DltReplayService}'s republish unit (Day-3 M1 ingest
 * DLT replay tooling). {@code replayAll()} drives a real Kafka consumer, so the
 * testable seam is {@link DltReplayService#republish(String, String)}: it
 * forwards a dead-letter record's original value onto {@code comm.ingest.v1}
 * with its original key, and skips blanks.
 */
@SuppressWarnings("unchecked")
class DltReplayServiceTest {

    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final DltReplayService service =
            new DltReplayService("localhost:9092", kafkaTemplate);

    @Test
    void republishesRecordValueOntoIngestTopicWithOriginalKey() {
        int sent = service.republish("sha256:key-1", "{\"event\":\"payload\"}");

        assertThat(sent).isEqualTo(1);
        verify(kafkaTemplate).send(eq(Topics.COMM_INGEST_V1), eq("sha256:key-1"), eq("{\"event\":\"payload\"}"));
    }

    @Test
    void skipsBlankValue() {
        assertThat(service.republish("key", "")).isZero();
        assertThat(service.republish("key", "   ")).isZero();
        verify(kafkaTemplate, never()).send(eq(Topics.COMM_INGEST_V1), eq("key"), eq(""));
    }

    @Test
    void skipsNullValue() {
        assertThat(service.republish("key", null)).isZero();
        verify(kafkaTemplate, never()).send(eq(Topics.COMM_INGEST_V1), eq("key"), org.mockito.ArgumentMatchers.isNull());
    }
}
