package com.smarsh.discoveryhub.audit.infrastructure.persistence;

import com.smarsh.discoveryhub.audit.application.HashChain;
import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import com.smarsh.discoveryhub.audit.domain.model.AuditQuery;
import com.smarsh.discoveryhub.audit.domain.port.AuditStorePort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mongo adapter for the append-only audit store. No update or delete is
 * exposed — the interface itself forbids it, and the Mongo user role should
 * grant {@code insert}+{@code find} only (architecture §2.6).
 *
 * <p><b>OOP — Adapter pattern + SOLID — DIP:</b> all MongoTemplate/Query
 * mechanics live here; the application layer stays persistence-agnostic.
 * {@code AuditEvent.fromDomain/toDomain} is the boundary translation — the
 * domain {@code AuditEntry} never carries Mongo annotations.
 */
@Component
public class MongoAuditStoreAdapter implements AuditStorePort {

    private final MongoTemplate mongo;
    private final HashChain hashChain;

    public MongoAuditStoreAdapter(MongoTemplate mongo, HashChain hashChain) {
        this.mongo = mongo;
        this.hashChain = hashChain;
    }

    @Override
    public void append(AuditEntry entry) {
        mongo.insert(AuditEvent.fromDomain(entry));
    }

    @Override
    public String latestHashForCase(String caseId) {
        Query query = Query.query(Criteria.where("caseId").is(caseId))
                .with(Sort.by(Sort.Direction.DESC, "occurredAt"))
                .limit(1);
        AuditEvent latest = mongo.findOne(query, AuditEvent.class);
        return latest != null ? latest.hash() : AuditEntry.GENESIS;
    }

    @Override
    public List<AuditEntry> findByCaseId(String caseId) {
        Query query = Query.query(Criteria.where("caseId").is(caseId))
                .with(Sort.by(Sort.Direction.ASC, "occurredAt"));
        return mongo.find(query, AuditEvent.class).stream().map(AuditEvent::toDomain).toList();
    }

    @Override
    public List<AuditEntry> findAll() {
        Query query = new Query().with(Sort.by(Sort.Direction.ASC, "occurredAt"));
        return mongo.find(query, AuditEvent.class).stream().map(AuditEvent::toDomain).toList();
    }

    @Override
    public List<AuditEntry> find(AuditQuery filter) {
        Query query = toFilterQuery(filter)
                .with(Sort.by(Sort.Direction.ASC, "occurredAt"))
                .skip((long) filter.effectivePage() * filter.effectiveSize())
                .limit(filter.effectiveSize());
        return mongo.find(query, AuditEvent.class).stream().map(AuditEvent::toDomain).toList();
    }

    @Override
    public long count(AuditQuery filter) {
        // Deliberately built without skip/limit — MongoTemplate honours both on
        // count, which would cap the total at one page and defeat the purpose.
        return mongo.count(toFilterQuery(filter), AuditEvent.class);
    }

    /** The filter half of an {@link AuditQuery}, with no sort/skip/limit applied. */
    private Query toFilterQuery(AuditQuery filter) {
        List<Criteria> criteria = new ArrayList<>();
        if (filter.caseId() != null) criteria.add(Criteria.where("caseId").is(filter.caseId()));
        if (filter.actor() != null) criteria.add(Criteria.where("actor").is(filter.actor()));
        if (filter.action() != null) criteria.add(Criteria.where("action").is(filter.action()));
        if (filter.targetType() != null) criteria.add(Criteria.where("targetType").is(filter.targetType()));
        if (filter.from() != null || filter.to() != null) {
            Criteria window = Criteria.where("occurredAt");
            if (filter.from() != null) window = window.gte(filter.from());
            if (filter.to() != null) window = window.lte(filter.to());
            criteria.add(window);
        }
        return criteria.isEmpty()
                ? new Query()
                : Query.query(new Criteria().andOperator(criteria.toArray(Criteria[]::new)));
    }

    @Override
    public boolean verifyChain(String caseId) {
        List<AuditEntry> entries = findByCaseId(caseId);
        String expectedPrev = AuditEntry.GENESIS;
        for (AuditEntry entry : entries) {
            if (!entry.prevHash().equals(expectedPrev)) return false;
            if (!hashChain.verify(entry)) return false;
            expectedPrev = entry.hash();
        }
        return true;
    }
}
