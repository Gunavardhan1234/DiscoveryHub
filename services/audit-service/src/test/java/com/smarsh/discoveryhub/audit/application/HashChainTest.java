package com.smarsh.discoveryhub.audit.application;

import com.smarsh.discoveryhub.audit.domain.model.AuditEntry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class HashChainTest {
    @Test void detectsTampering() {
        var entry = new AuditEntry("e1", Instant.parse("2025-01-01T00:00:00Z"), "actor", "ACTION",
                "Message", "m1", "case-1", null, "after", "corr", "GENESIS", null);
        var chain = new HashChain();
        var chained = new AuditEntry(entry.eventId(), entry.occurredAt(), entry.actor(), entry.action(),
                entry.targetType(), entry.targetId(), entry.caseId(), entry.before(), entry.after(),
                entry.correlationId(), entry.prevHash(), chain.hash(entry.prevHash(), entry));
        assertThat(chain.verify(chained)).isTrue();
        var tampered = new AuditEntry(chained.eventId(), chained.occurredAt(), chained.actor(), chained.action(),
                chained.targetType(), chained.targetId(), chained.caseId(), chained.before(), "tampered",
                chained.correlationId(), chained.prevHash(), chained.hash());
        assertThat(chain.verify(tampered)).isFalse();
    }

    @Test void canonicalizesMapsIndependentlyOfInsertionOrder() {
        Map<String, Object> beforeA = new LinkedHashMap<>();
        beforeA.put("z", 1);
        beforeA.put("a", 2);
        Map<String, Object> afterA = new LinkedHashMap<>();
        afterA.put("nested", Map.of("z", false, "a", true));

        Map<String, Object> beforeB = new LinkedHashMap<>();
        beforeB.put("a", 2);
        beforeB.put("z", 1);
        Map<String, Object> afterB = new LinkedHashMap<>();
        afterB.put("nested", new LinkedHashMap<>(Map.of("a", true, "z", false)));

        var first = new AuditEntry("e1", Instant.parse("2025-01-01T00:00:00Z"), "actor", "ACTION",
                "Message", "m1", "case-1", beforeA, afterA, "corr", "GENESIS", null);
        var second = new AuditEntry("e1", first.occurredAt(), first.actor(), first.action(),
                first.targetType(), first.targetId(), first.caseId(), beforeB, afterB,
                first.correlationId(), first.prevHash(), null);

        assertThat(new HashChain().hash(first.prevHash(), first))
                .isEqualTo(new HashChain().hash(second.prevHash(), second));
    }
}
