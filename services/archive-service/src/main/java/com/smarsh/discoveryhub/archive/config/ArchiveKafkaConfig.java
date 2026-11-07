package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer-side resilience for the archive {@code comm.ingest.v1} listener
 * (architecture §4, Day-2 hardening).
 *
 * <p>Defines a {@link DefaultErrorHandler} bean with an
 * {@link ExponentialBackOffWithMaxRetries} (3 attempts) and a
 * {@link DeadLetterPublishingRecoverer} that publishes exhausted records to
 * {@code comm.ingest.v1.DLT}. Spring Boot auto-wires this error handler into
 * the auto-configured {@code kafkaListenerContainerFactory}.
 *
 * <p>Because we define a {@code dltProducerFactory} (type
 * {@code ProducerFactory<String, String>}), Spring Boot's
 * {@code @ConditionalOnMissingBean(ProducerFactory.class)} backs off and the
 * auto-configured {@code ProducerFactory<String, Object>} / {@code KafkaTemplate<String, Object>}
 * are never created. We define them explicitly here from {@link KafkaProperties}
 * so {@link com.smarsh.discoveryhub.archive.infrastructure.messaging.KafkaStoredEventPublisher}
 * (which needs {@code KafkaTemplate<String, Object>} for JSON-serialized
 * {@code comm.stored.v1} events) can wire up.
 *
 * <p>A dedicated {@code dltKafkaListenerContainerFactory} is defined for the
 * DLT consumer, using a plain error handler (no DLT recoverer) so a
 * dead-lettered DLT record never cascades into a second DLT.
 */
@Configuration
public class ArchiveKafkaConfig {

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

    /**
     * Main producer factory for {@code comm.stored.v1} — uses the JSON serializer
     * from {@code spring.kafka.producer.*} properties (via {@link KafkaProperties}).
     * Defined explicitly because {@link #dltProducerFactory} shadows the
     * auto-configured {@code ProducerFactory}.
     */
    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Main KafkaTemplate for publishing {@code comm.stored.v1} with the JSON
     * serializer. Depends on {@link #producerFactory}.
     */
    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * The error handler for the main ingest listener. Spring Boot's
     * KafkaAutoConfiguration picks up this {@link DefaultErrorHandler} bean and
     * wires it into the auto-configured {@code kafkaListenerContainerFactory}.
     *
     * <p>The destination is resolved <em>explicitly</em> to {@link Topics#COMM_INGEST_V1_DLT}
     * rather than relying on {@link DeadLetterPublishingRecoverer}'s default
     * resolver, which suffixes the original topic with {@code "-dlt"} (e.g.
     * {@code comm.ingest.v1-dlt}) — a different topic from the one actually
     * pre-created ({@code comm.ingest.v1.DLT}, {@code KafkaTopicConfig}) and
     * consumed by {@link com.smarsh.discoveryhub.archive.infrastructure.messaging.IngestDltListener}
     * / replayed by {@code DltReplayService}. Since auto-topic-creation is
     * disabled on the broker, the mismatched default name previously failed
     * every publish with {@code UNKNOWN_TOPIC_OR_PARTITION} — the DLT listener
     * and {@code POST /admin/dlt/replay} never actually received anything.
     * Partition {@code -1} lets the producer pick a partition rather than
     * assuming the DLT topic has the same partition count as the source.
     */
    @Bean
    DefaultErrorHandler archiveErrorHandler(KafkaTemplate<String, String> dltKafkaTemplate) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(dltKafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.COMM_INGEST_V1_DLT, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                deadLetter, new ExponentialBackOffWithMaxRetries(3));
        handler.addNotRetryableExceptions(SerializationException.class);
        return handler;
    }

    /**
     * Dedicated listener container factory for the {@code comm.ingest.v1.DLT}
     * consumer. Uses a plain {@link DefaultErrorHandler} (retry 3× then skip + log)
     * with no DLT recoverer, so a dead-lettered DLT record never cascades.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new ExponentialBackOffWithMaxRetries(3));
        errorHandler.addNotRetryableExceptions(SerializationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
