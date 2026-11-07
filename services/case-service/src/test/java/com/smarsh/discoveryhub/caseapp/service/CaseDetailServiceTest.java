package com.smarsh.discoveryhub.caseapp.service;

import com.smarsh.discoveryhub.caseapp.api.dto.CaseDetailResponse;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseResponse;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.domain.port.HoldSummaryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FR-4.4: the Case Detail facade must report "the total count of held items
 * per case", resolved from hold-service via {@link HoldSummaryPort}.
 */
@ExtendWith(MockitoExtension.class)
class CaseDetailServiceTest {

    @Mock CaseService caseService;
    @Mock CustodianService custodianService;
    @Mock EvidenceService evidenceService;
    @Mock SavedSearchService savedSearchService;
    @Mock HoldSummaryPort holdSummary;

    private CaseDetailService service() {
        return new CaseDetailService(caseService, custodianService, evidenceService, savedSearchService, holdSummary);
    }

    @Test
    void getDetailIncludesHeldCountFromHoldSummaryPort() {
        UUID caseId = UUID.randomUUID();
        when(caseService.get(caseId)).thenReturn(new CaseResponse(
                caseId, "Case", "desc", MatterType.INVESTIGATION, "alice", CaseStatus.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now(), 0));
        when(custodianService.listCustodians(caseId)).thenReturn(List.of());
        when(evidenceService.listEvidence(caseId)).thenReturn(List.of());
        when(savedSearchService.list(caseId)).thenReturn(List.of());
        when(holdSummary.heldCount(caseId)).thenReturn(42L);

        CaseDetailResponse detail = service().getDetail(caseId);

        assertThat(detail.counts().heldCount()).isEqualTo(42L);
    }

    // Isolation via mocking the port, not the adapter: this test mocks HoldSummaryPort directly
    // rather than pointing RestHoldSummaryAdapter at an unreachable URL, so it verifies
    // CaseDetailService's own behavior (does it pass the count through untouched?) independently
    // of RestHoldSummaryAdapterTest, which separately proves the adapter's own fail-open logic.
    @Test
    void getDetailDefaultsHeldCountToZeroWhenHoldServiceUnavailable() {
        // RestHoldSummaryAdapter itself fails open to 0; this proves the
        // facade just passes that value through without any special-casing.
        UUID caseId = UUID.randomUUID();
        when(caseService.get(caseId)).thenReturn(new CaseResponse(
                caseId, "Case", "desc", MatterType.INVESTIGATION, "alice", CaseStatus.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now(), 0));
        when(custodianService.listCustodians(caseId)).thenReturn(List.of());
        when(evidenceService.listEvidence(caseId)).thenReturn(List.of());
        when(savedSearchService.list(caseId)).thenReturn(List.of());
        when(holdSummary.heldCount(any())).thenReturn(0L);

        CaseDetailResponse detail = service().getDetail(caseId);

        assertThat(detail.counts().heldCount()).isZero();
    }
}
