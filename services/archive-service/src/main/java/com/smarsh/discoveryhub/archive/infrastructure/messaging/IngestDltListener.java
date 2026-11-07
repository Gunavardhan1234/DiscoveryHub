package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import com.smarsh.discoveryhub.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the {@code comm.ingest.v1.DLT} dead-letter topic and logs each record
 * so operators can see what failed (and later replay it). It runs in its own
 * consumer group ({@code archive-ingest-dlt}) so it never competes with the main
 * ingest consumer's offsets.
 */
@Component
public class IngestDltListener {

    private static final Logger log = LoggerFactory.getLogger(IngestDltListener.class);

    /** Standard Spring Kafka DLT header carrying the exception class name. */
    private static final String DLT_EXCEPTION_HEADER = "kafka_dlt-exception-fqcn";

    @KafkaListener(topics = Topics.COMM_INGEST_V1_DLT, groupId = "archive-ingest-dlt",
            containerFactory = "dltKafkaListenerContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("comm.ingest.v1 DLT received: topic-partition-offset={}, key={}, exception={}, value={}",
                record.topic() + "-" + record.partition() + "-" + record.offset(),
                record.key(),
                exceptionHeaderValue(record),
                truncate(record.value(), 500));
    }

    /** The DLT recoverer records the failure cause under this header, which
     *  makes the dead-letter log actionable. */
    private static String exceptionHeaderValue(ConsumerRecord<String, String> record) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(DLT_EXCEPTION_HEADER);
        String cause = header != null ? new String(header.value()) : "n/a";
        return truncate(cause, 300);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "<null>";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
