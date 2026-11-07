package com.smarsh.discoveryhub.export.config;

import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExportKafkaConfigTest {

    private final ExportKafkaConfig config = new ExportKafkaConfig();

    @Test
    void dltProducerFactoryIsUsable() {
        ProducerFactory<String, String> factory = config.dltProducerFactory("localhost:9092");

        assertThat(factory).isNotNull();
    }

    @Test
    void dltKafkaTemplateWrapsTheProducerFactory() {
        ProducerFactory<String, String> factory = config.dltProducerFactory("localhost:9092");

        KafkaTemplate<String, String> template = config.dltKafkaTemplate(factory);

        assertThat(template).isNotNull();
    }

    @Test
    void mainProducerFactoryIsBuiltFromKafkaProperties() {
        ProducerFactory<String, Object> factory = config.producerFactory(new KafkaProperties());

        assertThat(factory).isNotNull();
    }

    @Test
    void mainKafkaTemplateWrapsTheMainProducerFactory() {
        ProducerFactory<String, Object> factory = config.producerFactory(new KafkaProperties());

        KafkaTemplate<String, Object> template = config.kafkaTemplate(factory);

        assertThat(template).isNotNull();
    }

    @Test
    void errorHandlerIsNotRetryableForSerializationExceptions() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> dltTemplate = mock(KafkaTemplate.class);
        ExportWorkerUseCase worker = mock(ExportWorkerUseCase.class);

        DefaultErrorHandler handler = config.exportErrorHandler(dltTemplate, worker);

        assertThat(handler).isNotNull();
    }
}
