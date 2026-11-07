package com.smarsh.discoveryhub.audit.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
@Configuration
public class KafkaTopicConfig {
    @Bean
    NewTopic auditEventsTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.AUDIT_EVENTS_V1).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    NewTopic commIngestDlt(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return dlt(Topics.COMM_INGEST_V1, replicationFactor);
    }

    @Bean
    NewTopic commStoredDlt(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return dlt(Topics.COMM_STORED_V1, replicationFactor);
    }

    @Bean
    NewTopic holdCommandsDlt(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return dlt(Topics.HOLD_COMMANDS_V1, replicationFactor);
    }

    @Bean
    NewTopic holdAppliedDlt(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return dlt(Topics.HOLD_APPLIED_V1, replicationFactor);
    }

    @Bean
    NewTopic exportJobsDlt(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return dlt(Topics.EXPORT_JOBS_V1, replicationFactor);
    }

    @Bean
    NewTopic auditEventsDlt(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return dlt(Topics.AUDIT_EVENTS_V1, replicationFactor);
    }

    private NewTopic dlt(String topic, int replicationFactor) {
        return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(replicationFactor).build();
    }
}
