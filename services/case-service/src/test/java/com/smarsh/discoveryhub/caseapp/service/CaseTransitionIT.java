package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.CaseCreateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseResponse;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStateMachine;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.testsupport.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * First real business integration test: proves the State pattern rejects
 * invalid case transitions end-to-end through real Postgres + Flyway + JPA,
 * and that valid transitions persist. Skips when Docker is unavailable.
 *
 * <p>Kafka autoconfiguration is excluded (no broker in this test), so the
 * real {@code KafkaAuditEventPublisher} (which needs a {@code KafkaTemplate})
 * is swapped for a {@code @MockBean} — case-service's audit publishing is
 * unit-tested separately in {@link CaseServiceTest}.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
class CaseTransitionIT extends PostgresTestBase {

    @MockBean
    private AuditEventPublisher auditEventPublisher;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::dbUsername);
        registry.add("spring.datasource.password", PostgresTestBase::dbPassword);
    }

    @Autowired
    private CaseService caseService;

    @Test
    void fullLifecycleIsOneDirectionalAndSkipsAreRejected() {
        // create a case in DRAFT
        CaseResponse created = caseService.create(new CaseCreateRequest(
                "Project Kingfisher", "Litigation matter", MatterType.LITIGATION, "investigator@demo"));
        assertThat(created.status()).isEqualTo(CaseStatus.DRAFT);

        // invalid: DRAFT -> CLOSED skips ACTIVE and UNDER_REVIEW (FR-2.2 one-directional)
        assertThatThrownBy(() -> caseService.transition(created.id(), CaseStatus.CLOSED))
                .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class)
                .hasMessageContaining("from DRAFT to CLOSED");

        // valid: DRAFT -> ACTIVE
        CaseResponse active = caseService.transition(created.id(), CaseStatus.ACTIVE);
        assertThat(active.status()).isEqualTo(CaseStatus.ACTIVE);
        assertThat(active.version()).isGreaterThan(created.version());

        // re-read from the repository to prove it persisted
        assertThat(caseService.get(created.id()).status()).isEqualTo(CaseStatus.ACTIVE);

        // invalid: ACTIVE -> DRAFT is not in the transition table
        assertThatThrownBy(() -> caseService.transition(created.id(), CaseStatus.DRAFT))
                .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class)
                .hasMessageContaining("from ACTIVE to DRAFT");

        // invalid: ACTIVE -> CLOSED skips UNDER_REVIEW (FR-2.2 one-directional)
        assertThatThrownBy(() -> caseService.transition(created.id(), CaseStatus.CLOSED))
                .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class)
                .hasMessageContaining("from ACTIVE to CLOSED");

        // the case is unchanged after the rejected transitions
        assertThat(caseService.get(created.id()).status()).isEqualTo(CaseStatus.ACTIVE);

        // valid: ACTIVE -> UNDER_REVIEW
        CaseResponse review = caseService.transition(created.id(), CaseStatus.UNDER_REVIEW);
        assertThat(review.status()).isEqualTo(CaseStatus.UNDER_REVIEW);

        // invalid: UNDER_REVIEW -> ACTIVE goes backwards (FR-2.2 one-directional)
        assertThatThrownBy(() -> caseService.transition(created.id(), CaseStatus.ACTIVE))
                .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class)
                .hasMessageContaining("from UNDER_REVIEW to ACTIVE");

        // valid: UNDER_REVIEW -> CLOSED completes the lifecycle
        CaseResponse closed = caseService.transition(created.id(), CaseStatus.CLOSED);
        assertThat(closed.status()).isEqualTo(CaseStatus.CLOSED);
    }

    @Test
    void closedCaseIsTerminal() {
        CaseResponse created = caseService.create(new CaseCreateRequest(
                "Closed matter", "Terminal state test", MatterType.INVESTIGATION, "investigator@demo"));
        caseService.transition(created.id(), CaseStatus.ACTIVE);
        caseService.transition(created.id(), CaseStatus.UNDER_REVIEW);
        caseService.transition(created.id(), CaseStatus.CLOSED);

        // CLOSED -> anything is rejected
        assertThatThrownBy(() -> caseService.transition(created.id(), CaseStatus.ACTIVE))
                .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> caseService.transition(created.id(), CaseStatus.UNDER_REVIEW))
                .isInstanceOf(CaseStateMachine.InvalidCaseTransitionException.class);
    }
}
