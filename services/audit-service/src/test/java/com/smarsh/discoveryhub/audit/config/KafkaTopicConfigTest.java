package com.smarsh.discoveryhub.audit.config;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every business/DLT topic bean is defined with the expected name, partitions, and replicas. */
class KafkaTopicConfigTest {

    private final KafkaTopicConfig config = new KafkaTopicConfig();

    @Test
    void auditEventsTopicHasThreePartitions() {
        NewTopic topic = config.auditEventsTopic(2);
        assertThat(topic.name()).isEqualTo(Topics.AUDIT_EVENTS_V1);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 2);
    }

    @Test
    void everyDltTopicIsNamedWithDltSuffixAndOnePartition() {
        record Case(String topic, NewTopic newTopic) {}
        var cases = java.util.List.of(
                new Case(Topics.COMM_INGEST_V1, config.commIngestDlt(1)),
                new Case(Topics.COMM_STORED_V1, config.commStoredDlt(1)),
                new Case(Topics.HOLD_COMMANDS_V1, config.holdCommandsDlt(1)),
                new Case(Topics.HOLD_APPLIED_V1, config.holdAppliedDlt(1)),
                new Case(Topics.EXPORT_JOBS_V1, config.exportJobsDlt(1)),
                new Case(Topics.AUDIT_EVENTS_V1, config.auditEventsDlt(1)));

        for (Case c : cases) {
            assertThat(c.newTopic().name()).isEqualTo(c.topic() + ".DLT");
            assertThat(c.newTopic().numPartitions()).isEqualTo(1);
            assertThat(c.newTopic().replicationFactor()).isEqualTo((short) 1);
        }
    }
}
