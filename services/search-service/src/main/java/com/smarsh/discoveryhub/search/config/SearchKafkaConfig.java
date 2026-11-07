package com.smarsh.discoveryhub.search.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka consumer wiring for search-service.
 *
 * <ul>
 *   <li>One consumer group ({@code search-indexer}), independent of ingest/archive
 *       groups — so replay of {@code comm.stored.v1} rebuilds ES without affecting the
 *       durability consumer (architecture §4, fullplan Day 1 §Member 2).</li>
 *   <li>{@link DefaultErrorHandler} with exponential backoff (3 attempts) and a
 *       dead-letter topic ({@code comm.stored.v1.DLT}); non-retryable deserialization
 *       errors short-circuit to the DLT (architecture §4 "Consumer error handling").</li>
 *   <li>Batch listener mode: Spring Kafka delivers a list of records per poll and
 *       the listener bulk-indexes them in a single ES {@code BulkRequest}
 *       (architecture §search-freshness: "flushed on 500 docs or 2 s").</li>
 * </ul>
 *
 * <p><b>SOLID — SRP + DIP (composition root):</b> all Kafka infrastructure
 * decisions — batching, ack mode, retry policy, DLT destination — are declared
 * here, in one place, instead of being scattered across the three listeners.
 * The listeners just name {@code containerFactory = "searchKafkaListenerContainerFactory"}.
 * This is the Spring "configuration as composition root" idiom: the wiring
 * knows about concrete types so the business classes do not have to.
 *
 * <p><b>Design pattern — Singleton (container-managed):</b> each {@code @Bean}
 * is a single shared instance managed by the Spring container — the modern form
 * of the pattern, with no hand-rolled {@code getInstance()} or static state, so
 * it stays substitutable in tests.
 *
 * <p><b>Resiliency patterns encoded here (NFR-2):</b>
 * <ul>
 *   <li><b>Retry with exponential backoff</b> — {@code ExponentialBackOffWithMaxRetries(3)}:
 *       a transient ES blip is retried rather than dropped.</li>
 *   <li><b>Dead Letter Queue</b> — after retries are exhausted the record is
 *       published to {@code comm.stored.v1.DLT} instead of blocking the
 *       partition, so one poison message cannot stall the whole index.</li>
 *   <li><b>Fail-fast on non-retryable errors</b> —
 *       {@code addNotRetryableExceptions(SerializationException.class)}: an
 *       unparseable payload will never succeed, so retrying it three times
 *       just wastes time; it short-circuits straight to the DLT.</li>
 *   <li><b>Consumer-group isolation (bulkhead)</b> — the {@code search-indexer}
 *       group is independent of archive's durability consumer, so replaying the
 *       topic to rebuild ES cannot disturb the source-of-truth write path.</li>
 * </ul>
 *
 * <p>The DLT destination is resolved explicitly (see the comment in the factory
 * below) rather than relying on the recoverer's default {@code -dlt} suffix —
 * a real bug that silently swallowed every dead-letter publish, because
 * auto-topic-creation is disabled on the broker.
 */
@Configuration
public class SearchKafkaConfig {

    /**
     * Dead-letter topic for {@code comm.stored.v1} (poison-pill index/parse
     * failures). Created explicitly because the broker disables auto-topic
     * creation.
     */
    @Bean
    NewTopic commStoredDltTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.COMM_STORED_V1_DLT).partitions(6).replicas(replicationFactor).build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> searchKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        // Destination resolved explicitly to Topics.COMM_STORED_V1_DLT — the
        // recoverer's default resolver suffixes the source topic with "-dlt"
        // (e.g. comm.stored.v1-dlt), a different, never-created topic; with
        // auto-topic-creation disabled that silently failed every dead-letter
        // publish. Partition -1 lets the producer pick a partition rather than
        // assuming the DLT topic shares the source topic's partition count.
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.COMM_STORED_V1_DLT, -1));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetter, new ExponentialBackOffWithMaxRetries(3));
        errorHandler.addNotRetryableExceptions(SerializationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
