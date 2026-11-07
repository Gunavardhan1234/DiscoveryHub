package com.smarsh.discoveryhub.search.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarsh.discoveryhub.events.EventEnvelope;
import com.smarsh.discoveryhub.events.HoldApplied;
import com.smarsh.discoveryhub.search.domain.service.SearchService;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HoldIndexerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SearchService searchService = mock(SearchService.class);
    private final HoldIndexer indexer = new HoldIndexer(mapper, searchService);

    private String envelopeJson(HoldApplied applied) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), "HOLD_APPLIED", 1, Instant.now(), applied.holdId(), "corr-1", "system", applied));
    }

    @Test
    void emptyBatchIsANoOp() {
        indexer.onBatch(List.of());

        verify(searchService, never()).applyHold(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void nullBatchIsANoOp() {
        indexer.onBatch(null);

        verify(searchService, never()).applyHold(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void coalescesMultipleApplyEventsForTheSameHoldIntoOneCall() throws Exception {
        HoldApplied e1 = new HoldApplied("hold-1", "case-1", HoldApplied.PLACE, List.of("m1"), true, false, 50);
        HoldApplied e2 = new HoldApplied("hold-1", "case-1", HoldApplied.PLACE, List.of("m2"), true, false, 100);

        indexer.onBatch(List.of(envelopeJson(e1), envelopeJson(e2)));

        verify(searchService).applyHold(eq("hold-1"), eq(List.of("m1", "m2")), eq(true));
    }

    @Test
    void separatesApplyAndReleaseEventsForDifferentHolds() throws Exception {
        HoldApplied apply = new HoldApplied("hold-1", "case-1", HoldApplied.PLACE, List.of("m1"), true, true, 100);
        HoldApplied release = new HoldApplied("hold-2", "case-1", HoldApplied.RELEASE, List.of("m2"), false, true, 100);

        indexer.onBatch(List.of(envelopeJson(apply), envelopeJson(release)));

        verify(searchService).applyHold("hold-1", List.of("m1"), true);
        verify(searchService).applyHold("hold-2", List.of("m2"), false);
    }

    @Test
    void eventsWithNullHoldIdAreSkipped() throws Exception {
        HoldApplied noHoldId = new HoldApplied(null, "case-1", HoldApplied.PLACE, List.of("m1"), true, true, 100);

        indexer.onBatch(List.of(envelopeJson(noHoldId)));

        verify(searchService, never()).applyHold(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void malformedJsonThrowsSerializationException() {
        assertThatThrownBy(() -> indexer.onBatch(List.of("not json")))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("hold.applied.v1");
    }
}
