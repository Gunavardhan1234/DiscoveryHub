package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaMessageDeletedEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaMessageDeletedEventPublisher publisher = new KafkaMessageDeletedEventPublisher(kafkaTemplate);

    @Test
    void publishesToMessageDeletedTopicKeyedByMessageId() {
        publisher.publishDeleted("msg-1", "EMAIL", "disposition");

        var captor = org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(eq(Topics.MESSAGE_DELETED_V1), eq("msg-1"), captor.capture());
        MessageDeletedEvent payload = (MessageDeletedEvent) captor.getValue().payload();
        assertThat(payload.messageId()).isEqualTo("msg-1");
        assertThat(payload.commType()).isEqualTo("EMAIL");
        assertThat(payload.reason()).isEqualTo("disposition");
        assertThat(captor.getValue().entityId()).isEqualTo("msg-1");
    }
}
