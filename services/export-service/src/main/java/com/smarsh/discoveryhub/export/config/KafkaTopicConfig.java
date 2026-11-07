package com.smarsh.discoveryhub.export.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {
    @Bean
    NewTopic exportJobsTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.EXPORT_JOBS_V1).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    NewTopic exportJobsDltTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.EXPORT_JOBS_V1_DLT).partitions(3).replicas(replicationFactor).build();
    }
}
