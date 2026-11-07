package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaHoldAppliedPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaHoldAppliedPublisher publisher = new KafkaHoldAppliedPublisher(kafkaTemplate);

    @Test
    void publishesToHoldAppliedTopicKeyedByGivenKey() {
        HoldApplied payload = new HoldApplied("hold-1", "case-1", HoldApplied.PLACE, List.of("m1"), true, true, 100);

        publisher.publish(payload, "hold-1");

        var captor = org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(eq(Topics.HOLD_APPLIED_V1), eq("hold-1"), captor.capture());
        assertThat(captor.getValue().payload()).isEqualTo(payload);
        assertThat(captor.getValue().actor()).isEqualTo("archive-hold-applier");
    }
}
