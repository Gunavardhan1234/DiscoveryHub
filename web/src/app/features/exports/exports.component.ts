import { Component, NgZone, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ExportService } from '../../core/services/export.service';
import { CaseService } from '../../core/services/case.service';
import { Case, ExportJob } from '../../core/api.model';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';

/**
 * Exports screen (FR-6). POST /exports returns 202 QUEUED; the worker streams
 * progress over SSE (`GET /exports/{id}/stream`, Observer) so the row moves
 * QUEUED → RUNNING(pct) → COMPLETED live. A slow poll remains as fallback for
 * missed events. COMPLETED rows show a pre-signed download button (15-min TTL)
 * and a server-side verify button.
 */
@Component({
  selector: 'app-exports',
  standalone: true,
  imports: [FormsModule, StatusChipComponent, EmptyStateComponent],
  template: `
    <div class="page-head">
      <h2>Exports</h2>
      <button type="button" class="sm" (click)="toggleRequest()">+ Request export</button>
    </div>

    @if (showRequest()) {
      <div class="card" style="margin-bottom:1rem">
        <div class="card-title">Request export — async</div>
        <div class="form-row">
          <label>Case</label>
          <select name="caseId" [(ngModel)]="caseId">
            <option value="" disabled>Select a case…</option>
            @for (c of openCases();track c.id) {
              <option [value]="c.id">{{ c.name }} ({{ c.status }})</option>
            }
          </select>
          <p class="faint" style="margin:0.25rem 0 0">Closed cases are read-only (FR-2.5) and excluded here.</p>
        </div>
        <div class="form-actions">
          <button type="button" class="secondary sm" (click)="toggleRequest()">Cancel</button>
          <button type="button" class="sm" [disabled]="!caseId" (click)="request()">Request</button>
        </div>
      </div>
    }

    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>Job</th><th>Case</th><th>Status</th><th>Progress</th><th>Package</th><th style="text-align:right">Actions</th></tr>
        </thead>
        <tbody>
          @for (j of jobs();track j.jobId) {
            <tr>
              <td><strong>{{ j.jobId }}</strong></td>
              <td class="faint">{{ j.caseId || '—' }}</td>
              <td><app-status-chip [value]="j.status" /></td>
              <td>
                @if (j.status === 'RUNNING' || j.status === 'QUEUED') {
                  <div class="progress"><span [style.width.%]="j.progressPct"></span></div>
                  <span class="faint">{{ j.progressPct }}%</span>
                } @else {
                  <span class="faint">{{ j.progressPct }}%</span>
                }
              </td>
              <td class="faint">{{ j.packageS3Key || '—' }}</td>
              <td style="text-align:right">
                @if (j.status === 'COMPLETED') {
                  <button type="button" class="ghost sm" (click)="download(j)">Download</button>
                  <button type="button" class="ghost sm" (click)="verify(j)">Verify</button>
                }
                @if (j.status === 'FAILED') {
                  <button type="button" class="ghost sm" (click)="retry(j)">Retry</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6"><app-empty-state icon="📦" title="No export jobs" hint="Request an export to package a case's evidence." /></td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class ExportsComponent implements OnInit, OnDestroy {
  private readonly exportService = inject(ExportService);
  private readonly caseService = inject(CaseService);
  private readonly route = inject(ActivatedRoute);
  private readonly zone = inject(NgZone);

  readonly jobs = signal<ExportJob[]>([]);
  readonly cases = signal<Case[]>([]);
  readonly showRequest = signal(false);
  caseId = '';
  private timer?: ReturnType<typeof setInterval>;
  private readonly streams = new Map<string, EventSource>();

  ngOnInit(): void {
    this.load();
    this.loadCases();
    this.timer = setInterval(() => this.load(), 5000);
    const preselect = this.route.snapshot.queryParamMap.get('caseId');
    if (preselect) {
      this.caseId = preselect;
      this.showRequest.set(true);
    }
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
    this.streams.forEach((s) => s.close());
    this.streams.clear();
  }

  load(): void {
    this.exportService.list().subscribe({
      next: (j) => {
        this.jobs.set(j ?? []);
        const inFlight = (j ?? []).filter((x) => x.status === 'QUEUED' || x.status === 'RUNNING');
        inFlight.forEach((x) => this.subscribeToProgress(x.jobId));
        // Stop polling once nothing is in flight; SSE keeps live rows moving.
        if (inFlight.length === 0 && this.timer) {
          clearInterval(this.timer);
          this.timer = undefined;
        }
      },
      error: () => this.jobs.set([]),
    });
  }

  /** Observer side of the SSE stream: one EventSource per in-flight job. */
  private subscribeToProgress(jobId: string): void {
    if (this.streams.has(jobId)) return;
    const source = new EventSource(`/api/exports/${jobId}/stream`);
    this.streams.set(jobId, source);
    source.addEventListener('progress', (event: MessageEvent) => {
      const update = JSON.parse(event.data) as ExportJob;
      this.zone.run(() => {
        this.jobs.update((list) => list.map((j) => (j.jobId === update.jobId ? { ...j, ...update } : j)));
        if (update.status === 'COMPLETED' || update.status === 'FAILED') this.closeStream(jobId);
      });
    });
    source.onerror = () => {
      // Server closed (terminal state) or connection lost — fall back to polling.
      this.closeStream(jobId);
      this.zone.run(() => {
        if (!this.timer) this.timer = setInterval(() => this.load(), 5000);
      });
    };
  }

  private closeStream(jobId: string): void {
    this.streams.get(jobId)?.close();
    this.streams.delete(jobId);
  }

  loadCases(): void {
    this.caseService.list({ size: 100 }).subscribe({
      next: (p) => this.cases.set(p.content ?? []),
      error: () => this.cases.set([]),
    });
  }

  /** Closed cases are read-only (FR-2.5, server-enforced) — excluded from
   *  the request form so the "Case is closed" 409 is never the demo's
   *  surprise. */
  openCases(): Case[] {
    return this.cases().filter((c) => c.status !== 'CLOSED');
  }

  toggleRequest(): void {
    this.showRequest.update((v) => !v);
  }

  request(): void {
    this.exportService.request(this.caseId).subscribe(() => {
      this.caseId = '';
      this.showRequest.set(false);
      this.load();
      if (!this.timer) this.timer = setInterval(() => this.load(), 5000);
    });
  }

  download(j: ExportJob): void {
    this.exportService.downloadUrl(j.jobId).subscribe({
      next: (d) => { window.open(d.url, '_blank'); this.load(); },
      error: () => {},
    });
  }

  verify(j: ExportJob): void {
    this.exportService.verify(j.jobId).subscribe({
      next: (v) => {
        const msg = v.valid ? 'Package verified ✓ — all hashes match' : 'Verification FAILED — package may be tampered';
        alert(msg);
      },
      error: () => {},
    });
  }

  retry(j: ExportJob): void {
    this.exportService.retry(j.jobId).subscribe({
      next: () => {
        this.load();
        if (!this.timer) this.timer = setInterval(() => this.load(), 5000);
      },
      error: () => {},
    });
  }
}
