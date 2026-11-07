package com.smarsh.discoveryhub.hold.service;

import com.smarsh.discoveryhub.common.ValidationException;
import com.smarsh.discoveryhub.events.AuditEventPayload;
import com.smarsh.discoveryhub.hold.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyEntity;
import com.smarsh.discoveryhub.hold.infrastructure.persistence.RetentionPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-7.1: retention policy changes (FR-5.1) must be audited — a demo can
 * retune a policy live (e.g. "set EMAIL retention to 1 minute") and that
 * change must be traceable in the chain of custody.
 */
@ExtendWith(MockitoExtension.class)
class RetentionPolicyServiceTest {

    @Mock RetentionPolicyRepository policies;
    @Mock AuditEventPublisher audit;
    @InjectMocks RetentionPolicyService service;

    @Test
    void upsertPublishesAuditWithBeforeAndAfter() {
        RetentionPolicyEntity existing = new RetentionPolicyEntity();
        existing.commType = "EMAIL";
        existing.duration = "PT168H";
        when(policies.findById("EMAIL")).thenReturn(Optional.of(existing));

        service.upsert("EMAIL", "PT2M", "compliance-officer");

        verify(policies).save(any(RetentionPolicyEntity.class));
        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq("EMAIL"));
        AuditEventPayload payload = captor.getValue();
        assertThat(payload.action()).isEqualTo("RETENTION_POLICY_UPDATED");
        assertThat(payload.actor()).isEqualTo("compliance-officer");
        assertThat(payload.targetId()).isEqualTo("EMAIL");
        assertThat(payload.before()).contains("PT168H");
        assertThat(payload.after()).contains("PT2M");
    }

    @Test
    void upsertOnNewPolicyHasNullBefore() {
        when(policies.findById("CHAT")).thenReturn(Optional.empty());

        service.upsert("CHAT", "PT1M", "compliance-officer");

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(audit).publish(captor.capture(), eq("CHAT"));
        assertThat(captor.getValue().before()).isNull();
    }

    @Test
    void upsertWithInvalidDurationRejectsAndNeverAudits() {
        assertThatThrownBy(() -> service.upsert("EMAIL", "not-a-duration", "someone"))
                .isInstanceOf(ValidationException.class);

        verify(audit, org.mockito.Mockito.never()).publish(any(), any());
    }
}
