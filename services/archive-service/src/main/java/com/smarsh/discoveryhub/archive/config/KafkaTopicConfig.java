package com.smarsh.discoveryhub.archive.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import java.util.Map;

@Configuration
public class KafkaTopicConfig {
    @Bean
    NewTopic commStoredTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.COMM_STORED_V1).partitions(6).replicas(replicationFactor)
                .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)).build();
    }

    @Bean
    NewTopic holdAppliedTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.HOLD_APPLIED_V1).partitions(3).replicas(replicationFactor).build();
    }

    @Bean
    NewTopic messageDeletedTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.MESSAGE_DELETED_V1).partitions(3).replicas(replicationFactor).build();
    }

    /** Dead-letter topic for {@code comm.ingest.v1} (architecture §4 "Consumer
     *  error handling"). Records that exhaust the {@link DefaultErrorHandler}
     *  retries are published here for inspection/replay. Created explicitly
     *  because the broker disables auto-topic creation. */
    @Bean
    NewTopic commIngestDltTopic(@Value("${discoveryhub.kafka.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(Topics.COMM_INGEST_V1_DLT).partitions(6).replicas(replicationFactor).build();
    }
}
