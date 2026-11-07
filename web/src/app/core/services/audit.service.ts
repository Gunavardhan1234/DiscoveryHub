import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { AuditEvent, ChainVerification } from '../api.model';

/** A page of audit events plus the total match count for the same filters. */
export interface AuditPage {
  items: AuditEvent[];
  total: number;
}

/**
 * Angular client for audit-service. Audit is read-only by construction (no
 * write API exists — FR-7.3), so this service only exposes GETs.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  /**
   * One page of audit events. audit-service keeps the body a bare array and
   * reports the unpaged total in `X-Total-Count`, so we observe the full
   * response and normalise both into an {@link AuditPage}. If the header is
   * absent (older service, or a proxy that strips it) the total falls back to
   * the number of rows returned, which degrades to single-page behaviour.
   */
  list(filters: { caseId?: string; actor?: string; action?: string; targetType?: string; from?: string; to?: string; page?: number; size?: number } = {}): Observable<AuditPage> {
    let p = new HttpParams();
    if (filters.caseId) p = p.set('caseId', filters.caseId);
    if (filters.actor) p = p.set('actor', filters.actor);
    if (filters.action) p = p.set('action', filters.action);
    if (filters.targetType) p = p.set('targetType', filters.targetType);
    if (filters.from) p = p.set('from', filters.from);
    if (filters.to) p = p.set('to', filters.to);
    if (filters.page != null) p = p.set('page', String(filters.page));
    if (filters.size != null) p = p.set('size', String(filters.size));
    return this.http.get<AuditEvent[]>('/api/audit', { params: p, observe: 'response' }).pipe(
      map((res) => {
        const items = res.body ?? [];
        const header = res.headers.get('X-Total-Count');
        const total = header == null ? NaN : Number(header);
        return { items, total: Number.isFinite(total) && total >= 0 ? total : items.length };
      }),
    );
  }

  verifyChain(caseId: string): Observable<ChainVerification> {
    let p = new HttpParams().set('caseId', caseId);
    return this.http.get<ChainVerification>('/api/audit/verify-chain', { params: p });
  }
}
