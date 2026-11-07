package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaStoredEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaStoredEventPublisher publisher = new KafkaStoredEventPublisher(kafkaTemplate);

    @Test
    void publishesToCommStoredTopicKeyedByGivenKey() {
        CommunicationEvent payload = new CommunicationEvent("msg-1", "key-1", "ext-1", "EMAIL", "hi", "body",
                "thread-1", null, "c-1", List.of(), Instant.now(), List.of(), false);

        publisher.publishStored(payload, "key-1");

        var captor = org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(eq(Topics.COMM_STORED_V1), eq("key-1"), captor.capture());
        assertThat(captor.getValue().payload()).isEqualTo(payload);
        assertThat(captor.getValue().entityId()).isEqualTo("msg-1");
    }
}
