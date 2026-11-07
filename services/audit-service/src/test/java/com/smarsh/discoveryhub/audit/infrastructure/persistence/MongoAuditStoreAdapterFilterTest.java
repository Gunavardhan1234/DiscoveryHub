package com.smarsh.discoveryhub.audit.infrastructure.persistence;

import com.smarsh.discoveryhub.audit.application.HashChain;
import com.smarsh.discoveryhub.audit.domain.model.AuditQuery;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-7.4 filtered reads: the adapter must translate every filter into Mongo criteria + paging. */
class MongoAuditStoreAdapterFilterTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoAuditStoreAdapter adapter = new MongoAuditStoreAdapter(mongo, new HashChain());

    @Test
    void buildsCriteriaForEveryFilter() {
        when(mongo.find(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEvent.class)))
                .thenReturn(List.of());
        Instant from = Instant.parse("2026-09-08T00:00:00Z");
        Instant to = Instant.parse("2026-09-09T23:59:59Z");

        adapter.find(new AuditQuery("case-42", "i-harriet", "HOLD_PLACED", "HOLD", from, to, 2, 50));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(AuditEvent.class));
        Query query = captor.getValue();

        @SuppressWarnings("unchecked")
        List<Document> and = (List<Document>) query.getQueryObject().get("$and");
        Map<String, Object> flattened = and.stream()
                .flatMap(doc -> doc.entrySet().stream())
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(flattened)
                .containsEntry("caseId", "case-42")
                .containsEntry("actor", "i-harriet")
                .containsEntry("action", "HOLD_PLACED")
                .containsEntry("targetType", "HOLD");
        Document occurredAt = (Document) flattened.get("occurredAt");
        assertThat(occurredAt.get("$gte")).isEqualTo(from);
        assertThat(occurredAt.get("$lte")).isEqualTo(to);
        assertThat(query.getSkip()).isEqualTo(100);
        assertThat(query.getLimit()).isEqualTo(50);
        assertThat(query.getSortObject()).containsKey("occurredAt");
    }

    @Test
    void countReusesTheFiltersButDropsSkipAndLimit() {
        when(mongo.count(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEvent.class))).thenReturn(7L);

        long total = adapter.count(new AuditQuery("case-42", null, null, null, null, null, 2, 50));

        assertThat(total).isEqualTo(7L);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captor.capture(), eq(AuditEvent.class));
        Query query = captor.getValue();
        // MongoTemplate honours skip/limit on count — carrying them over would
        // cap the total at a single page.
        assertThat(query.getSkip()).isZero();
        assertThat(query.getLimit()).isZero();
        assertThat(query.getQueryObject().toJson()).contains("case-42");
    }

    @Test
    void noFiltersMeansUnconstrainedQueryWithCappedSize() {
        when(mongo.find(org.mockito.ArgumentMatchers.any(Query.class), eq(AuditEvent.class)))
                .thenReturn(List.of());

        adapter.find(new AuditQuery(null, null, null, null, null, null, null, 100_000));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(captor.capture(), eq(AuditEvent.class));
        assertThat(captor.getValue().getQueryObject().isEmpty()).isTrue();
        assertThat(captor.getValue().getLimit()).isEqualTo(AuditQuery.MAX_SIZE);
        assertThat(captor.getValue().getSkip()).isZero();
    }
}
