import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { Hold, HoldCreate, HoldStatus, RetentionPolicy } from '../api.model';

/** Angular client for hold-service (legal-hold lifecycle + retention policy). */
@Injectable({ providedIn: 'root' })
export class HoldService {
  private readonly http = inject(HttpClient);

  /** @param caseId when set, scopes the list server-side (FR-4.4) instead of returning every hold. */
  list(caseId?: string): Observable<Hold[]> {
    const params = caseId ? new HttpParams().set('caseId', caseId) : undefined;
    return this.http.get<Hold[]>('/api/holds', { params });
  }

  get(id: string): Observable<Hold> {
    return this.http.get<Hold>(`/api/holds/${id}`);
  }

  place(body: HoldCreate): Observable<Hold> {
    return this.http.post<Hold>('/api/holds', body);
  }

  release(id: string): Observable<Hold> {
    return this.http.delete<Hold>(`/api/holds/${id}`);
  }

  /** hold-service owns retention policy; reachable through the /api/holds/** gateway route. */
  retentionPolicies(): Observable<RetentionPolicy[]> {
    return this.http
      .get<{ policies: RetentionPolicy[] }>('/api/holds/retention-policy')
      .pipe(map((r) => r?.policies ?? []));
  }

  upsertRetention(commType: string, duration: string): Observable<RetentionPolicy> {
    return this.http.put<RetentionPolicy>(`/api/holds/retention-policy/${commType}`, { duration });
  }
}
