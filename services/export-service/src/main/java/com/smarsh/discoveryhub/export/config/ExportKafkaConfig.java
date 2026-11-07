package com.smarsh.discoveryhub.export.config;

import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consumer-side resilience for the export worker (execution-plan M5 Day 3),
 * mirroring ArchiveKafkaConfig: a {@link DefaultErrorHandler} with
 * {@link ExponentialBackOffWithMaxRetries} (3 attempts) whose recoverer
 * dead-letters the record to {@code export.jobs.v1.DLT} <em>and</em> marks the
 * job FAILED so the UI/SSE stream shows the terminal state instead of a job
 * stuck in RUNNING.
 *
 * <p>Defining the String/String DLT producer makes Boot's
 * {@code @ConditionalOnMissingBean(ProducerFactory.class)} back off, so the
 * JSON producer for {@code export.jobs.v1}/{@code audit.events.v1} is defined
 * explicitly here from {@link KafkaProperties} (same trick as archive-service).
 */
@Configuration
public class ExportKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(ExportKafkaConfig.class);

    @Bean
    ProducerFactory<String, String> dltProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, String> dltKafkaTemplate(ProducerFactory<String, String> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /** Main JSON producer ({@code export.jobs.v1}, {@code audit.events.v1}) from spring.kafka.producer.*. */
    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(new HashMap<>(kafkaProperties.buildProducerProperties()));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Picked up by Boot's KafkaAutoConfiguration for the listener container
     * factory: 3 attempts with exponential backoff, then dead-letter to
     * {@code export.jobs.v1.DLT} + mark the job FAILED.
     *
     * <p>The destination is resolved <em>explicitly</em> to {@link Topics#EXPORT_JOBS_V1_DLT}
     * rather than relying on {@link DeadLetterPublishingRecoverer}'s default
     * resolver, which suffixes the original topic with {@code "-dlt"} (e.g.
     * {@code export.jobs.v1-dlt}) — a different topic from the one actually
     * pre-created ({@code export.jobs.v1.DLT}, {@code KafkaTopicConfig}).
     * Since auto-topic-creation is disabled on the broker, publishing to the
     * mismatched default name previously failed with
     * {@code UNKNOWN_TOPIC_OR_PARTITION} on every attempt, so the record was
     * never recovered and {@link #markJobFailed} was never called — a stuck
     * job would retry the dead-letter publish forever instead of reaching
     * FAILED. Partition {@code -1} lets the producer pick a partition rather
     * than assuming the DLT topic has the same partition count as the source.
     */
    @Bean
    DefaultErrorHandler exportErrorHandler(KafkaTemplate<String, String> dltKafkaTemplate,
                                           ExportWorkerUseCase worker) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(dltKafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.EXPORT_JOBS_V1_DLT, -1));
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            deadLetter.accept(record, ex);
            markJobFailed(worker, record.key(), ex);
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new ExponentialBackOffWithMaxRetries(3));
        handler.addNotRetryableExceptions(SerializationException.class);
        return handler;
    }

    private static void markJobFailed(ExportWorkerUseCase worker, Object key, Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        try {
            worker.markFailed(UUID.fromString(String.valueOf(key)), cause.getMessage());
        } catch (Exception e) {
            log.warn("Could not mark export job '{}' FAILED after DLT: {}", key, e.getMessage());
        }
    }
}
