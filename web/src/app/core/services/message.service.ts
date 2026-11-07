import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { ArchiveMessage } from '../api.model';
import { SKIP_ERROR_TOAST } from '../interceptors/error.interceptor';

/** Mirrors the export worker's `ArchiveMessageHttpAdapter.BATCH_SIZE` — the
 *  bulk read passes ids in a query param, so they are chunked to keep the URL
 *  bounded. */
export const MESSAGE_BATCH_SIZE = 200;

/** Angular client for archive-service's authoritative message reads (Mongo is
 *  the source of truth, never the ES projection). Used by the Disposition
 *  screen's guided demo to prove a held message survives a pass (200) and an
 *  expired unheld one doesn't (404), and by Case Detail to show per-message
 *  hold status (FR-4.4).
 *
 *  <p>Both calls suppress the global error toast: a 404 is an expected outcome
 *  for the demo, and hold status is supplementary information on the case
 *  screen — archive being down should degrade that column, not toast an error
 *  over a page that otherwise works (NFR-2). */
@Injectable({ providedIn: 'root' })
export class MessageService {
  private readonly http = inject(HttpClient);

  exists(id: string): Observable<boolean> {
    return this.http
      .get(`/api/messages/${id}`, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) })
      .pipe(
        map(() => true),
        catchError(() => of(false)),
      );
  }

  /**
   * Bulk read for up to a batch of ids (`GET /api/messages?ids=a,b,c`).
   * Unknown ids are simply absent from the result. Returns `[]` rather than
   * failing if archive-service is unreachable.
   */
  byIds(ids: string[]): Observable<ArchiveMessage[]> {
    const unique = [...new Set(ids.filter((id) => !!id))];
    if (unique.length === 0) return of([]);
    const batches: string[][] = [];
    for (let i = 0; i < unique.length; i += MESSAGE_BATCH_SIZE) {
      batches.push(unique.slice(i, i + MESSAGE_BATCH_SIZE));
    }
    return forkJoin(batches.map((batch) => this.fetchBatch(batch))).pipe(map((r) => r.flat()));
  }

  private fetchBatch(ids: string[]): Observable<ArchiveMessage[]> {
    return this.http
      .get<{ messages: ArchiveMessage[] }>('/api/messages', {
        params: new HttpParams().set('ids', ids.join(',')),
        context: new HttpContext().set(SKIP_ERROR_TOAST, true),
      })
      .pipe(
        map((r) => r?.messages ?? []),
        catchError(() => of([])),
      );
  }
}
