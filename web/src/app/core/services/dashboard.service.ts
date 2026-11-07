import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DashboardCounts } from '../api.model';

/**
 * Dashboard counts: total messages (from ES aggregation), active cases, active
 * holds, exports completed. The backend M1 will wire this to an aggregation
 * endpoint; until then it returns zeros so the dashboard renders cleanly.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  counts(): Observable<DashboardCounts> {
    return this.http.get<DashboardCounts>('/api/dashboard/counts');
  }
}
