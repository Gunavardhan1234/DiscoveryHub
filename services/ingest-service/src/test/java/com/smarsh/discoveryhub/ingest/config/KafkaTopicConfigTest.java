package com.smarsh.discoveryhub.ingest.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicConfigTest {

    @Test
    void declaresCommIngestTopicWithSixPartitions() {
        NewTopic topic = new KafkaTopicConfig().commIngestTopic(3);

        assertThat(topic.name()).isEqualTo(Topics.COMM_INGEST_V1);
        assertThat(topic.numPartitions()).isEqualTo(6);
        assertThat(topic.replicationFactor()).isEqualTo((short) 3);
    }
}
