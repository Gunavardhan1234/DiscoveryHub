package com.smarsh.discoveryhub.archive.application;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Replay tooling for the {@code comm.ingest.v1.DLT} dead-letter topic (Day-3 M1).
 * Records that exhausted retries are logged by {@code IngestDltListener}; this
 * service republishes them back onto {@code comm.ingest.v1} so the archive
 * ingest consumer can reprocess them — the standard Kafka DLT replay pattern.
 *
 * <p>It reads the DLT from the very beginning in a throwaway consumer group
 * (random UUID + {@code auto.offset-reset=earliest}), then forwards each
 * record's original value to {@code comm.ingest.v1} with its original key.
 * Replay is idempotent downstream: the ingest consumer's Mongo upsert is a
 * {@code $setOnInsert} no-op for already-stored messages, so re-running a
 * fixed record never duplicates.
 */
@Service
public class DltReplayService {

    private static final Logger log = LoggerFactory.getLogger(DltReplayService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    private final String bootstrapServers;
    private final KafkaTemplate<String, String> ingestKafkaTemplate;

    public DltReplayService(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Qualifier("dltKafkaTemplate") KafkaTemplate<String, String> ingestKafkaTemplate) {
        this.bootstrapServers = bootstrapServers;
        this.ingestKafkaTemplate = ingestKafkaTemplate;
    }

    /**
     * Replay every record on {@code comm.ingest.v1.DLT} back onto
     * {@code comm.ingest.v1}.
     *
     * @return the number of records replayed
     */
    public long replayAll() {
        Properties props = baseConsumerProps();
        long count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(Topics.COMM_INGEST_V1_DLT));
            consumer.poll(Duration.ZERO);
            consumer.seekToBeginning(consumer.assignment());

            int emptyPolls = 0;
            while (emptyPolls < 2) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    count += republish(record.key(), record.value());
                }
            }
        }
        log.info("DLT replay: forwarded {} records from {} to {}",
                count, Topics.COMM_INGEST_V1_DLT, Topics.COMM_INGEST_V1);
        return count;
    }

    /**
     * Forwards a single dead-letter record back to {@code comm.ingest.v1}.
     *
     * @return 1 if a non-empty value was republished, 0 otherwise
     */
    int republish(String key, String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        ingestKafkaTemplate.send(Topics.COMM_INGEST_V1, key, value);
        return 1;
    }

    private Properties baseConsumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ingest-dlt-replay-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}
