package com.smarsh.discoveryhub.hold.service;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.common.ValidationException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.hold.api.dto.RetentionPolicyResponse;
import com.smarsh.discoveryhub.hold.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Read/upsert for retention policies (FR-5.1). The policies are the per-type
 * retention configuration the {@link com.smarsh.discoveryhub.hold.domain.retention.RetentionService}
 * selects a strategy from. {@link RetentionSeeder} seeds the FR-5.1 values
 * (EMAIL=P2555D, CHAT=P1095D) on first boot; this upsert is what makes them
 * "settable to minutes" for a live demo, and an operator-set value is never
 * re-seeded on restart.
 */
@Service
@Transactional
public class RetentionPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyService.class);

    private final RetentionPolicyRepository policies;
    private final AuditEventPublisher audit;

    public RetentionPolicyService(RetentionPolicyRepository policies, AuditEventPublisher audit) {
        this.policies = policies;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public RetentionPolicyResponse list() {
        List<RetentionPolicyResponse.Policy> rows = policies.findAllByOrderByCommTypeAsc().stream()
                .map(RetentionPolicyResponse.Policy::from)
                .toList();
        return new RetentionPolicyResponse(rows);
    }

    public RetentionPolicyResponse.Policy upsert(String commType, String isoDuration) {
        return upsert(commType, isoDuration, null);
    }

    public RetentionPolicyResponse.Policy upsert(String commType, String isoDuration, String updatedBy) {
        if (commType == null || commType.isBlank()) {
            throw new ValidationException("commType must not be blank");
        }
        Duration parsed;
        try {
            parsed = Duration.parse(isoDuration);
        } catch (Exception e) {
            throw new ValidationException("duration must be an ISO-8601 duration (e.g. PT2M): " + e.getMessage());
        }
        if (parsed.isNegative() || parsed.isZero()) {
            throw new ValidationException("duration must be positive");
        }

        RetentionPolicyEntity entity = policies.findById(commType).orElseGet(RetentionPolicyEntity::new);
        String previousDuration = entity.duration;
        entity.commType = commType;
        entity.duration = isoDuration;
        entity.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        policies.save(entity);
        publishAudit(commType, previousDuration, isoDuration, updatedBy);
        return RetentionPolicyResponse.Policy.from(entity);
    }

    /**
     * Best-effort audit publish (FR-7.1: "retention policy updated" — part of
     * the disposition/retention chain of custody). Never fails the request.
     */
    private void publishAudit(String commType, String before, String after, String updatedBy) {
        try {
            audit.publish(new AuditEventPayload(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    updatedBy != null && !updatedBy.isBlank() ? updatedBy : Actor.current(),
                    "RETENTION_POLICY_UPDATED",
                    "RETENTION_POLICY",
                    commType,
                    null,
                    before == null ? null : "{\"duration\":\"" + before + "\"}",
                    "{\"duration\":\"" + after + "\"}",
                    CorrelationId.current()), commType);
        } catch (Exception e) {
            log.warn("Failed to publish RETENTION_POLICY_UPDATED audit event for {}", commType, e);
        }
    }
}
