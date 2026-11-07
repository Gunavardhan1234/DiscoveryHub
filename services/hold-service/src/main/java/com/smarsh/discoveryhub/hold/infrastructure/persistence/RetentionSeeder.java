package com.smarsh.discoveryhub.hold.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds the retention policies on first boot with the realistic values FR-5.1
 * names: EMAIL 7 years, CHAT 3 years. Idempotent — only inserts rows that do
 * not already exist, so operator-set policies are never overwritten.
 *
 * <p><strong>Why not minute-level seeds.</strong> These were originally
 * {@code EMAIL=PT2M}/{@code CHAT=PT1M} so a pass could be watched live, but the
 * corpus generator back-dates {@code sentAt} across the last 30 days — so every
 * seeded message was instantly past its cutoff and the 120s
 * {@link com.smarsh.discoveryhub.hold.service.DispositionScheduler} tick would
 * delete the entire corpus. That forced the scheduled sweep to be shipped
 * disabled, which left FR-5.2 ("a <em>scheduled</em> disposition process")
 * unproven. Seeding past the generator's 30-day window instead means the
 * schedule can run continuously and still be safe.
 *
 * <p>FR-5.1's "settable to minutes" is unaffected — {@code PUT
 * /api/holds/retention-policy/{commType}} accepts any {@code Duration}-parseable
 * value, so a demo can still drop CHAT to {@code PT1M} on the spot. What the
 * seed controls is only the <em>starting</em> state.
 *
 * <p>Durations are expressed in days because the column is parsed with
 * {@code java.time.Duration}, which rejects {@code P7Y}.
 */
@Component
public class RetentionSeeder {

    private static final Logger log = LoggerFactory.getLogger(RetentionSeeder.class);

    /**
     * The generator's back-dating window. Any seeded retention shorter than this
     * makes the whole corpus immediately eligible for disposition, so the
     * defaults below must stay comfortably longer.
     */
    static final int CORPUS_BACKDATE_DAYS = 30;

    /** commType -> ISO-8601 duration. EMAIL 7 years, CHAT 3 years (FR-5.1). */
    static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("EMAIL", "P2555D");
        DEFAULTS.put("CHAT", "P1095D");
    }

    private final RetentionPolicyRepository policies;

    public RetentionSeeder(RetentionPolicyRepository policies) {
        this.policies = policies;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
            if (policies.findById(e.getKey()).isEmpty()) {
                RetentionPolicyEntity entity = new RetentionPolicyEntity();
                entity.commType = e.getKey();
                entity.duration = e.getValue();
                entity.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
                policies.save(entity);
                log.info("Seeded retention policy {}={}", e.getKey(), e.getValue());
            }
        }
    }
}
