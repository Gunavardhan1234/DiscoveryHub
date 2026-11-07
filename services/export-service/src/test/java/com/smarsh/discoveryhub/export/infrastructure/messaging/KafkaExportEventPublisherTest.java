package com.smarsh.discoveryhub.export.infrastructure.messaging;

import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.ExportJob;
import com.smarsh.discoveryhub.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaExportEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaExportEventPublisher publisher = new KafkaExportEventPublisher(kafkaTemplate);

    @Test
    void publishesToExportJobsTopicKeyedByJobId() {
        ExportJob payload = ExportJob.queued("job-1", "case-1", null);

        publisher.publishExportRequested(payload, "job-1");

        var captor = org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(eq(Topics.EXPORT_JOBS_V1), eq("job-1"), captor.capture());
        assertThat(captor.getValue().payload()).isEqualTo(payload);
        assertThat(captor.getValue().entityId()).isEqualTo("job-1");
    }
}
