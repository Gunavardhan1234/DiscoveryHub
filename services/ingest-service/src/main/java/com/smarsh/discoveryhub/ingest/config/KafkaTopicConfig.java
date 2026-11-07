package com.smarsh.discoveryhub.ingest.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {
    @Bean
    NewTopic commIngestTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.COMM_INGEST_V1).partitions(6).replicas(replicationFactor).build();
    }
}
