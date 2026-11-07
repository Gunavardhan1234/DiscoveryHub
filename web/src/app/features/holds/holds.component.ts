import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HoldService } from '../../core/services/hold.service';
import { CaseService } from '../../core/services/case.service';
import { Case, Hold, HoldCreate, HoldStatus } from '../../core/api.model';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { PaginatorComponent } from '../../shared/paginator/paginator.component';

/** Status filter vocabulary. `LIVE` is the grouped "still protecting messages"
 *  option — PENDING/ACTIVE/RELEASING, the same set `canRelease` allows and the
 *  same definition of "active holds" the dashboard's remote-counts adapter
 *  uses — so "active vs released" is one click rather than three. */
type HoldStatusFilter = '' | 'LIVE' | HoldStatus;

/**
 * Holds screen (FR-4). The hold lifecycle is async from the first commit:
 * POST /holds returns 202 PENDING immediately; the archive hold-applier
 * reports progress over hold.applied.v1 and we poll GET /holds/{id} to show
 * PENDING → ACTIVE → RELEASING → RELEASED. A release button is shown only for
 * non-terminal holds. The bottom card edits retention policies (Strategy
 * pattern, minute-level durations for the demo).
 */
@Component({
  selector: 'app-holds',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusChipComponent, EmptyStateComponent, PaginatorComponent],
  template: `
    <div class="page-head">
      <h2>Legal Holds</h2>
      <button type="button" class="sm" (click)="toggleWizard()">+ Place hold</button>
    </div>

    @if (filterCaseId()) {
      <p class="muted" style="margin-top:-0.5rem">
        Showing holds for case <strong>{{ filterCaseId() }}</strong> — {{ filteredHeldTotal() }} held item{{ filteredHeldTotal() === 1 ? '' : 's' }} total (FR-4.4) —
        <a routerLink="/holds" (click)="clearFilters()">clear filter</a>
      </p>
    }

    @if (showWizard()) {
      <div class="card" style="margin-bottom:1rem">
        <div class="card-title">Place hold — async from the first commit</div>
        <p class="muted" style="margin:0 0 0.75rem">Returns 202 PENDING. The archive hold-applier resolves the scope to messages and reports back; the hold flips to ACTIVE once applied.</p>
        <div class="form-row">
          <label>Case</label>
          <select name="caseId" [(ngModel)]="draft.caseId">
            <option value="" disabled>Select a case…</option>
            @for (c of openCases(); track c.id) {
              <option [value]="c.id">{{ c.name }} ({{ c.status }})</option>
            }
          </select>
          <p class="faint" style="margin:0.25rem 0 0">Closed cases are read-only (FR-2.5) and excluded here.</p>
        </div>
        <div class="form-grid">
          <div class="form-row">
            <label>Custodians (comma-separated, empty = all)</label>
            <input name="custodians" [(ngModel)]="custodians" placeholder="c-0007, c-0012" />
          </div>
          <div class="form-row">
            <label>Terms (free text)</label>
            <input name="terms" [(ngModel)]="draft.terms" placeholder="wire transfer" />
          </div>
          <div class="form-row">
            <label>From</label>
            <input type="datetime-local" name="from" [(ngModel)]="draft.from" />
          </div>
          <div class="form-row">
            <label>To</label>
            <input type="datetime-local" name="to" [(ngModel)]="draft.to" />
          </div>
        </div>
        <div class="form-actions">
          <button type="button" class="secondary sm" (click)="toggleWizard()">Cancel</button>
          <button type="button" class="sm" [disabled]="!draft.caseId" (click)="place()">Place hold</button>
        </div>
      </div>
    }

    <div class="card" style="margin-bottom:1rem">
      <div class="filters" style="gap:0.6rem">
        <select name="statusFilter" [(ngModel)]="statusFilter" (change)="onFilterChange()" style="width:auto">
          <option value="">All statuses ({{ holds().length }})</option>
          <option value="LIVE">In force — not released ({{ countLive() }})</option>
          <option value="RELEASED">Released ({{ countOf('RELEASED') }})</option>
          <option value="PENDING">Pending ({{ countOf('PENDING') }})</option>
          <option value="ACTIVE">Active ({{ countOf('ACTIVE') }})</option>
          <option value="RELEASING">Releasing ({{ countOf('RELEASING') }})</option>
          <option value="FAILED">Failed ({{ countOf('FAILED') }})</option>
        </select>
        <select name="caseFilter" [value]="filterCaseId() ?? ''" (change)="onCaseChange($event)" style="width:auto">
          <option value="">All cases</option>
          @for (c of cases(); track c.id) {
            <option [value]="c.id">{{ c.name }} ({{ c.status }})</option>
          }
        </select>
        @if (statusFilter || filterCaseId()) {
          <button type="button" class="ghost sm" (click)="clearFilters()">Clear</button>
        }
      </div>
    </div>

    @if (filteredHolds().length > 0) {
      <app-paginator
        [page]="page"
        [size]="size"
        [total]="filteredHolds().length"
        (pageChange)="goToPage($event)"
        (sizeChange)="changeSize($event)" />
    }

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Hold</th><th>Case</th><th>Status</th><th>Scope</th>
            <th>Progress</th><th style="text-align:right">Actions</th>
          </tr>
        </thead>
        <tbody>
          @for (h of pagedHolds(); track h.holdId) {
            <tr>
              <td><strong>{{ h.holdId }}</strong><br><span class="faint">{{ h.terms || 'all custodians' }}</span></td>
              <td class="faint">{{ h.caseId }}</td>
              <td><app-status-chip [value]="h.status" /></td>
              <td>
                <span class="muted">{{ h.custodians.length ? h.custodians.join(', ') : 'all' }}</span>
                @if (h.from) { <br><span class="faint">{{ h.from }} → {{ h.to || '∞' }}</span> }
              </td>
              <td>
                @if (h.status === 'PENDING' || h.status === 'RELEASING') {
                  <div class="progress"><span [style.width.%]="h.progressPct"></span></div>
                  <span class="faint">{{ h.appliedCount }}/{{ h.heldCount || '?' }} ({{ h.progressPct }}%)</span>
                } @else {
                  <span class="faint">{{ h.heldCount }} held</span>
                }
              </td>
              <td style="text-align:right">
                @if (canRelease(h)) {
                  <button type="button" class="ghost sm danger" (click)="release(h)">Release</button>
                } @else if (h.status === 'RELEASED') {
                  <span class="faint">done</span>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6">
              @if (holds().length > 0) {
                <app-empty-state icon="🛡️" title="No holds match these filters" hint="Try 'All statuses', or clear the filters to see every hold." />
              } @else {
                <app-empty-state icon="🛡️" title="No legal holds" hint="Place a hold to protect messages from disposition." />
              }
            </td></tr>
          }
        </tbody>
      </table>
    </div>
    @if (filteredHolds().length > 0) {
      <app-paginator
        variant="nav"
        [page]="page"
        [size]="size"
        [total]="filteredHolds().length"
        (pageChange)="goToPage($event)" />
    }

    <div class="card" style="margin-top:1.5rem">
      <div class="card-title">Retention policy (Strategy pattern, per comm type)</div>
      <p class="muted" style="margin:0 0 0.75rem">How long messages of each type are kept before disposition. Shown in years; stored as ISO-8601 durations (P365D = 1 year).</p>
      <table>
        <thead><tr><th>Comm type</th><th>Retention (years)</th><th></th></tr></thead>
        <tbody>
          @for (p of policies();track p.commType) {
            <tr>
              <td><strong>{{ p.commType }}</strong></td>
              <td>
                <input type="number" name="dur-{{ p.commType }}" [(ngModel)]="p.years"
                       min="0" step="1" style="width:6rem" />
              </td>
              <td style="text-align:right"><button type="button" class="ghost sm" (click)="savePolicy(p)">Save</button></td>
            </tr>
          } @empty {
            <tr><td colspan="3"><span class="faint">Loading…</span></td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class HoldsComponent implements OnInit {
  private readonly holdService = inject(HoldService);
  private readonly caseService = inject(CaseService);
  private readonly route = inject(ActivatedRoute);

  readonly holds = signal<Hold[]>([]);
  readonly cases = signal<Case[]>([]);
  /** View model: years as a number for the UI, converted to/from the
   *  ISO-8601 duration string the backend stores (P365D ↔ 1). */
  readonly policies = signal<{ commType: string; years: number }[]>([]);
  readonly showWizard = signal(false);
  /** Set from ?caseId= (FR-4.4): scopes the list server-side instead of
   *  showing every hold across every case. */
  readonly filterCaseId = signal<string | null>(null);

  custodians = '';
  draft: HoldCreate = { caseId: '', custodians: [], terms: '' };

  /** Status filter + paging are both client-side: GET /api/holds takes only
   *  `caseId` and returns a bare array with no page/size params, so there is
   *  nothing to push server-side. The case filter *is* server-side, since that
   *  param already exists (FR-4.4). */
  statusFilter: HoldStatusFilter = '';
  page = 0;
  size = 25;

  ngOnInit(): void {
    const preselect = this.route.snapshot.queryParamMap.get('caseId');
    this.filterCaseId.set(preselect || null);
    this.loadHolds();
    this.loadCases();
    this.loadPolicies();
    if (preselect) {
      this.draft.caseId = preselect;
      this.showWizard.set(true);
    }
  }

  loadHolds(): void {
    this.holdService.list(this.filterCaseId() ?? undefined).subscribe({
      next: (h) => {
        this.holds.set([...(h ?? [])].reverse());
        this.clampPage();
      },
      error: () => { this.holds.set([]); this.page = 0; },
    });
  }

  /** Holds matching the status filter. `LIVE` groups the three non-terminal
   *  states so "still protecting messages" is one selection. */
  filteredHolds(): Hold[] {
    const all = this.holds();
    if (!this.statusFilter) return all;
    if (this.statusFilter === 'LIVE') return all.filter((h) => this.isLive(h));
    return all.filter((h) => h.status === this.statusFilter);
  }

  /** The current page of `filteredHolds` — filter first, then slice, so paging
   *  never shows a short page with matches stranded on the next one. */
  pagedHolds(): Hold[] {
    const start = this.page * this.size;
    return this.filteredHolds().slice(start, start + this.size);
  }

  private isLive(h: Hold): boolean {
    return h.status === 'PENDING' || h.status === 'ACTIVE' || h.status === 'RELEASING';
  }

  countLive(): number {
    return this.holds().filter((h) => this.isLive(h)).length;
  }

  countOf(status: HoldStatus): number {
    return this.holds().filter((h) => h.status === status).length;
  }

  /** Status is filtered client-side, so this never refetches — just restart at
   *  page 0, since the old offset points into a different result set. */
  onFilterChange(): void {
    this.page = 0;
  }

  /** The case filter is the server-side `?caseId=` param, so this reloads. */
  onCaseChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.filterCaseId.set(value || null);
    this.page = 0;
    this.loadHolds();
  }

  clearFilters(): void {
    this.statusFilter = '';
    this.filterCaseId.set(null);
    this.page = 0;
    this.loadHolds();
  }

  goToPage(p: number): void {
    this.page = Math.max(0, p);
    this.clampPage();
  }

  /** A bigger/smaller window invalidates the offset, so restart at page 0 —
   *  same rule as the audit and cases screens. */
  changeSize(size: number): void {
    this.size = size;
    this.page = 0;
  }

  /** Client-side paging has no server to re-clamp the offset, so keep the page
   *  in range ourselves when a release/poll shortens the filtered list. */
  private clampPage(): void {
    const last = Math.max(0, Math.ceil(this.filteredHolds().length / this.size) - 1);
    if (this.page > last) {
      this.page = last;
    }
  }

  /** Sum of heldCount across the filtered case's non-RELEASED holds — the
   *  same "currently held" semantics as case-service's HoldSummaryPort. */
  filteredHeldTotal(): number {
    return this.holds()
      .filter((h) => h.status !== 'RELEASED')
      .reduce((sum, h) => sum + (h.heldCount || 0), 0);
  }

  loadCases(): void {
    this.caseService.list({ size: 100 }).subscribe({
      next: (p) => this.cases.set(p.content ?? []),
      error: () => this.cases.set([]),
    });
  }

  /** Closed cases are read-only (FR-2.5, server-enforced) — excluded from
   *  the wizard so the "Case is closed" 409 is never the demo's surprise. */
  openCases(): Case[] {
    return this.cases().filter((c) => c.status !== 'CLOSED');
  }

  loadPolicies(): void {
    this.holdService.retentionPolicies().subscribe({
      next: (p) =>
        this.policies.set(
          (p ?? []).map((x) => ({ commType: x.commType, years: this.isoToYears(x.duration) })),
        ),
      error: () => this.policies.set([]),
    });
  }

  toggleWizard(): void {
    this.showWizard.update((v) => !v);
  }

  place(): void {
    const custodians = this.custodians.trim()
      ? this.custodians.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
      : [];
    const body: HoldCreate = {
      caseId: this.draft.caseId,
      custodians,
      terms: this.draft.terms || undefined,
      from: this.draft.from ? new Date(this.draft.from).toISOString() : undefined,
      to: this.draft.to ? new Date(this.draft.to).toISOString() : undefined,
    };
    this.holdService.place(body).subscribe(() => {
      this.draft = { caseId: '', custodians: [], terms: '' };
      this.custodians = '';
      this.showWizard.set(false);
      this.loadHolds();
      // Poll once after a short delay so the PENDING row appears with progress.
      setTimeout(() => this.loadHolds(), 1500);
    });
  }

  canRelease(h: Hold): boolean {
    return h.status === 'PENDING' || h.status === 'ACTIVE' || h.status === 'RELEASING';
  }

  release(h: Hold): void {
    this.holdService.release(h.holdId).subscribe(() => {
      this.loadHolds();
      setTimeout(() => this.loadHolds(), 1500);
    });
  }

  savePolicy(p: { commType: string; years: number }): void {
    this.holdService
      .upsertRetention(p.commType, this.yearsToIso(p.years))
      .subscribe(() => this.loadPolicies());
  }

  /** ISO-8601 duration (e.g. "P365D", "PT168H") → whole years. The backend
   *  stores days/hours/minutes (java.time.Duration can't parse P1Y), so we
   *  convert via total hours → days → years. Non-day units are rounded. */
  private isoToYears(iso: string): number {
    if (!iso) return 0;
    // Extract the numeric value + unit from the ISO-8601 string.
    const match = iso.match(/^P(?:T?)([\d.]+)([DHMS])$/i);
    if (!match) return 0;
    const value = parseFloat(match[1]);
    const unit = match[2].toUpperCase();
    // Normalize everything to days, then divide by 365.
    const days = unit === 'D' ? value : unit === 'H' ? value / 24 : unit === 'M' ? value / 1440 : value / 86400;
    return Math.round(days / 365);
  }

  /** Years → ISO-8601 duration as days (P365D for 1 year). The backend uses
   *  java.time.Duration.parse() which can't handle P1Y, so we emit P{n*365}D. */
  private yearsToIso(years: number): string {
    const days = Math.max(0, Math.round(years * 365));
    return days === 0 ? 'PT0S' : `P${days}D`;
  }
}
