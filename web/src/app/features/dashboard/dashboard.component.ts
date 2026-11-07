import { Component, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DashboardService } from '../../core/services/dashboard.service';
import { DashboardCounts } from '../../core/api.model';

/**
 * Dashboard (FR-8.1). Top-line counts that frame the whole tool: total
 * messages, active cases, active holds, exports completed. Each stat links to
 * its screen. Errors are surfaced by the shared toast; we keep last-known
 * counts on failure.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="page-head">
      <h2>Dashboard</h2>
      <p class="subtitle">Platform-wide counts. Click a card to drill in.</p>
    </div>

    @if (error()) {
      <div class="card"><p class="muted">Couldn't load live counts — the dashboard service may be starting up.</p></div>
    }

    <div class="grid grid-4">
      <a class="card" routerLink="/search" style="text-decoration:none;color:inherit">
        <div class="stat">
          <span class="stat-value">{{ counts().totalMessages.toLocaleString() }}</span>
          <span class="stat-label">Total messages</span>
        </div>
      </a>
      <a class="card" routerLink="/cases" style="text-decoration:none;color:inherit">
        <div class="stat">
          <span class="stat-value">{{ counts().activeCases.toLocaleString() }}</span>
          <span class="stat-label">Active cases</span>
        </div>
      </a>
      <a class="card" routerLink="/holds" style="text-decoration:none;color:inherit">
        <div class="stat">
          <span class="stat-value">{{ counts().activeHolds.toLocaleString() }}</span>
          <span class="stat-label">Active holds</span>
        </div>
      </a>
      <a class="card" routerLink="/exports" style="text-decoration:none;color:inherit">
        <div class="stat">
          <span class="stat-value">{{ counts().exportsCompleted.toLocaleString() }}</span>
          <span class="stat-label">Exports completed</span>
        </div>
      </a>
    </div>

    <div class="grid grid-3" style="margin-top:1.5rem">
      <div class="card">
        <div class="card-title">Quick start</div>
        <p class="muted" style="margin:0">Search the corpus, open a case, place a legal hold, then prove a held message can't be deleted.</p>
        <p style="margin-top:0.75rem"><a routerLink="/search">Run a search →</a></p>
      </div>
      <div class="card">
        <div class="card-title">Retention</div>
        <p class="muted" style="margin:0">Per-comm-type retention durations, editable in minutes for the demo.</p>
        <p style="margin-top:0.75rem"><a routerLink="/disposition">Run disposition →</a></p>
      </div>
      <div class="card">
        <div class="card-title">Chain of custody</div>
        <p class="muted" style="margin:0">Append-only audit trail with a per-case hash chain you can re-verify.</p>
        <p style="margin-top:0.75rem"><a routerLink="/audit">View audit →</a></p>
      </div>
    </div>
  `,
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly dashboard = inject(DashboardService);

  readonly counts = signal<DashboardCounts>({ totalMessages: 0, activeCases: 0, activeHolds: 0, exportsCompleted: 0 });
  readonly error = signal(false);
  private timer: ReturnType<typeof setInterval> | undefined;

  ngOnInit(): void {
    this.refresh();
    // Refresh every 15s so the dashboard reflects live counts (Day-4 resiliency
    // demo: the total-messages card climbs as the archive consumer catches up).
    this.timer = setInterval(() => this.refresh(), 15_000);
  }

  ngOnDestroy(): void {
    if (this.timer) {
      clearInterval(this.timer);
    }
  }

  private refresh(): void {
    this.dashboard.counts().subscribe({
      next: (c) => { this.counts.set(c); this.error.set(false); },
      error: () => this.error.set(true),
    });
  }
}
