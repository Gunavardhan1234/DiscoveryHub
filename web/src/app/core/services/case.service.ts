import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Case,
  CaseCreate,
  CaseDetail,
  CaseStatus,
  CaseUpdate,
  Custodian,
  CustodianAdd,
  EvidenceBulkAddResult,
  EvidenceItem,
  Page,
  SavedSearch,
} from '../api.model';

/** Angular client for case-service (case CRUD + State-pattern transitions,
 *  plus the custodian/evidence/saved-search sub-resources used by the Case
 *  Detail screen). */
@Injectable({ providedIn: 'root' })
export class CaseService {
  private readonly http = inject(HttpClient);

  list(params: { status?: CaseStatus; q?: string; page?: number; size?: number } = {}): Observable<Page<Case>> {
    let p = new HttpParams();
    if (params.status) p = p.set('status', params.status);
    if (params.q) p = p.set('q', params.q);
    if (params.page != null) p = p.set('page', String(params.page));
    if (params.size != null) p = p.set('size', String(params.size));
    return this.http.get<Page<Case>>('/api/cases', { params: p });
  }

  get(id: string): Observable<Case> {
    return this.http.get<Case>(`/api/cases/${id}`);
  }

  create(body: CaseCreate): Observable<Case> {
    return this.http.post<Case>('/api/cases', body);
  }

  /** Partial update of a case's mutable metadata (FR-2.3 / FR-7.1 CASE_UPDATED).
   *  A CLOSED case 409s with CASE_CLOSED (FR-2.5) — surfaced via the error toast. */
  update(id: string, body: CaseUpdate): Observable<Case> {
    return this.http.put<Case>(`/api/cases/${id}`, body);
  }

  transition(id: string, to: CaseStatus): Observable<Case> {
    return this.http.post<Case>(`/api/cases/${id}/transition`, { to });
  }

  /** Facade endpoint: case + custodians + evidence + saved searches + counts in one call. */
  detail(id: string): Observable<CaseDetail> {
    return this.http.get<CaseDetail>(`/api/cases/${id}/detail`);
  }

  addCustodian(caseId: string, body: CustodianAdd): Observable<Custodian> {
    return this.http.post<Custodian>(`/api/cases/${caseId}/custodians`, body);
  }

  removeCustodian(caseId: string, custodianId: string): Observable<void> {
    return this.http.delete<void>(`/api/cases/${caseId}/custodians/${custodianId}`);
  }

  addEvidence(caseId: string, messageId: string, source?: string): Observable<EvidenceItem> {
    return this.http.post<EvidenceItem>(`/api/cases/${caseId}/evidence`, { messageId, source });
  }

  addEvidenceBulk(caseId: string, messageIds: string[], source?: string): Observable<EvidenceBulkAddResult> {
    return this.http.post<EvidenceBulkAddResult>(`/api/cases/${caseId}/evidence/bulk`, { messageIds, source });
  }

  /** Removes a single evidence item (FR-7.1 EVIDENCE_REMOVED). A CLOSED case
   *  409s with CASE_CLOSED (FR-2.5) — surfaced via the error toast. */
  removeEvidence(caseId: string, evidenceId: string): Observable<void> {
    return this.http.delete<void>(`/api/cases/${caseId}/evidence/${evidenceId}`);
  }

  deleteSavedSearch(caseId: string, searchId: string): Observable<void> {
    return this.http.delete<void>(`/api/cases/${caseId}/saved-searches/${searchId}`);
  }

  saveSearch(caseId: string, name: string, spec: string): Observable<SavedSearch> {
    return this.http.post<SavedSearch>(`/api/cases/${caseId}/saved-searches`, { name, spec });
  }
}
