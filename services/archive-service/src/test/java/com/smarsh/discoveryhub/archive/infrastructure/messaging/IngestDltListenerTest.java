package com.smarsh.discoveryhub.archive.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/** Logs every dead-lettered record; must never throw regardless of header/value shape. */
class IngestDltListenerTest {

    private final IngestDltListener listener = new IngestDltListener();

    @Test
    void logsARecordWithTheExceptionHeaderPresent() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "comm.ingest.v1.DLT", 0, 42L, "key-1", "{\"broken\":");
        record.headers().add(new RecordHeader("kafka_dlt-exception-fqcn",
                "com.fasterxml.jackson.core.JsonParseException".getBytes()));

        assertThatCode(() -> listener.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void logsARecordWithNoExceptionHeader() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "comm.ingest.v1.DLT", 0, 1L, "key-1", "value");

        assertThatCode(() -> listener.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void truncatesAVeryLongValueWithoutThrowing() {
        String longValue = "x".repeat(1000);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "comm.ingest.v1.DLT", 0, 2L, "key-1", longValue);

        assertThatCode(() -> listener.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void handlesANullValueWithoutThrowing() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "comm.ingest.v1.DLT", 0, 3L, "key-1", null);

        assertThatCode(() -> listener.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
