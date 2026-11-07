package com.smarsh.discoveryhub.search.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchKafkaConfigTest {

    private final SearchKafkaConfig config = new SearchKafkaConfig();

    @Test
    void dltTopicHasSixPartitions() {
        NewTopic topic = config.commStoredDltTopic(2);

        assertThat(topic.name()).isEqualTo(Topics.COMM_STORED_V1_DLT);
        assertThat(topic.numPartitions()).isEqualTo(6);
        assertThat(topic.replicationFactor()).isEqualTo((short) 2);
    }

    @Test
    void listenerContainerFactoryIsBatchModeWithBatchAck() {
        @SuppressWarnings("unchecked")
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.searchKafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
    }
}
