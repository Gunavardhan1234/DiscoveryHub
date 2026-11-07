package com.smarsh.discoveryhub.hold.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic holdCommandsTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.HOLD_COMMANDS_V1).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    NewTopic holdAppliedTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.HOLD_APPLIED_V1).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    NewTopic caseClosedTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.CASE_CLOSED_V1).partitions(3).replicas(replicationFactor).build();
    }
}
