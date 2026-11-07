package com.smarsh.discoveryhub.archive.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ArchiveKafkaConfigTest {

    private final ArchiveKafkaConfig config = new ArchiveKafkaConfig();

    @Test
    void dltProducerFactoryIsUsable() {
        assertThat(config.dltProducerFactory("localhost:9092")).isNotNull();
    }

    @Test
    void dltKafkaTemplateWrapsTheProducerFactory() {
        ProducerFactory<String, String> factory = config.dltProducerFactory("localhost:9092");

        assertThat(config.dltKafkaTemplate(factory)).isNotNull();
    }

    @Test
    void mainProducerFactoryIsBuiltFromKafkaProperties() {
        assertThat(config.producerFactory(new KafkaProperties())).isNotNull();
    }

    @Test
    void mainKafkaTemplateWrapsTheMainProducerFactory() {
        ProducerFactory<String, Object> factory = config.producerFactory(new KafkaProperties());

        assertThat(config.kafkaTemplate(factory)).isNotNull();
    }

    @Test
    void archiveErrorHandlerIsUsable() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> dltTemplate = mock(KafkaTemplate.class);

        DefaultErrorHandler handler = config.archiveErrorHandler(dltTemplate);

        assertThat(handler).isNotNull();
    }

    @Test
    void dltListenerContainerFactoryUsesRecordAckMode() {
        @SuppressWarnings("unchecked")
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.dltKafkaListenerContainerFactory(consumerFactory);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
    }
}
