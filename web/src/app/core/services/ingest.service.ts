import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { IngestMessage, IngestResult } from '../api.model';

/** Angular client for ingest-service's single-message endpoint. Used by the
 *  Disposition screen's guided demo to create a fresh, freely-timestamped
 *  message it can immediately place a hold on / let expire — the bulk
 *  10k-message corpus generator has no UI hook and isn't suitable for a
 *  demo where you need control over exactly which two messages are used. */
@Injectable({ providedIn: 'root' })
export class IngestService {
  private readonly http = inject(HttpClient);

  ingest(body: IngestMessage): Observable<IngestResult> {
    return this.http.post<IngestResult>('/api/messages/ingest', body);
  }
}
