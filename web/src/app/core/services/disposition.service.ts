import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DispositionRun } from '../api.model';
import { SKIP_ERROR_TOAST } from '../interceptors/error.interceptor';

/**
 * Angular client for hold-service's disposition endpoints (M4). Used by the
 * Disposition screen to trigger a run and list past runs. Mounted under
 * /api/holds/disposition so calls ride the gateway's existing /api/holds/**
 * route. The {@code @Scheduled} job calls the same backend; this is the manual
 * trigger for the Day-3 demo ("set email retention to 2 min, trigger, show
 * deleted: N").
 */
@Injectable({ providedIn: 'root' })
export class DispositionService {
  private readonly http = inject(HttpClient);

  run(): Observable<DispositionRun> {
    return this.http.post<DispositionRun>('/api/holds/disposition/run', {});
  }

  /** Same as {@link run} but suppresses the global error toast: the guided
   *  demo treats a 409 (a scheduled tick already holds the lock) as a normal
   *  branch, not a failure, and reports it in its own step log instead. */
  runSilently(): Observable<DispositionRun> {
    return this.http.post<DispositionRun>(
      '/api/holds/disposition/run',
      {},
      { context: new HttpContext().set(SKIP_ERROR_TOAST, true) },
    );
  }

  list(): Observable<DispositionRun[]> {
    return this.http.get<DispositionRun[]>('/api/holds/disposition/runs');
  }

  latest(): Observable<DispositionRun> {
    return this.http.get<DispositionRun>('/api/holds/disposition/runs/latest');
  }
}
