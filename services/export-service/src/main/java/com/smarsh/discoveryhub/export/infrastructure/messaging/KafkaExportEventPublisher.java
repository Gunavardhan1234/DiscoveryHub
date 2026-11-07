package com.smarsh.discoveryhub.export.infrastructure.messaging;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.export.domain.port.ExportEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@code export.jobs.v1} with {@code jobId} as both the Kafka key and
 * the envelope {@code entityId} (events.md §5).
 */
@Component
public class KafkaExportEventPublisher implements ExportEventPublisher {

    private static final String EVENT_TYPE = "EXPORT_REQUESTED";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaExportEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishExportRequested(com.smarsh.discoveryhub.events.ExportJob payload, String kafkaKey) {
        EventEnvelope<com.smarsh.discoveryhub.events.ExportJob> envelope = new EventEnvelope<>(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                Instant.now(),
                payload.jobId(),
                CorrelationId.current(),
                Actor.current(),
                payload);
        kafkaTemplate.send(Topics.EXPORT_JOBS_V1, kafkaKey, envelope);
    }
}
