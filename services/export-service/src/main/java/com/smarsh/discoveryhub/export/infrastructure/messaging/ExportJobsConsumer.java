package com.smarsh.discoveryhub.export.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.ExportJob;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin Kafka adapter (events.md §5): consumes {@code export.jobs.v1} in the
 * {@code export-worker} group and hands the job id to
 * {@link ExportWorkerUseCase}. Any exception propagates to the
 * {@code DefaultErrorHandler} (3 attempts, exponential backoff, then
 * {@code export.jobs.v1.DLT} + job marked FAILED — see ExportKafkaConfig).
 */
@Component
public class ExportJobsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportJobsConsumer.class);
    private static final TypeReference<EventEnvelope<ExportJob>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final ExportWorkerUseCase worker;

    public ExportJobsConsumer(ObjectMapper mapper, ExportWorkerUseCase worker) {
        this.mapper = mapper;
        this.worker = worker;
    }

    @KafkaListener(topics = Topics.EXPORT_JOBS_V1,
            groupId = "${spring.kafka.consumer.group-id:export-worker}")
    public void onExportJob(String value) {
        EventEnvelope<ExportJob> envelope;
        try {
            envelope = mapper.readValue(value, EVENT_TYPE);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Cannot parse export.jobs.v1 payload", e);
        }
        CorrelationId.set(envelope.correlationId());
        Actor.set(envelope.actor());
        try {
            log.info("export.jobs.v1 received for job {}", envelope.payload().jobId());
            worker.process(UUID.fromString(envelope.payload().jobId()));
        } finally {
            CorrelationId.clear();
            Actor.clear();
        }
    }
}
