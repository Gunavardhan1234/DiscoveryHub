import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuditService } from '../../core/services/audit.service';
import { CaseService } from '../../core/services/case.service';
import { AuditEvent, ChainVerification, AUDIT_ACTIONS, AUDIT_TARGET_TYPES, Case } from '../../core/api.model';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { PaginatorComponent } from '../../shared/paginator/paginator.component';

/** Audit screen (FR-7): append-only chain of custody. Filter by case/actor/
 *  action/target-type/time; verify the per-case hash chain with one click.
 *  Read-only — no write API exists on audit-service. Every filter is a
 *  dropdown (or a date picker) so the user picks from the real vocabulary
 *  instead of guessing ISO strings and enum values. */
@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [FormsModule, EmptyStateComponent, PaginatorComponent],
  template: `
    <div class="page-head">
      <h2>Audit trail</h2>
      <p class="subtitle">Append-only. Each event is hashed into a per-case chain you can re-verify.</p>
    </div>

    <div class="card" style="margin-bottom:1rem">
      <div class="card-title">Filter</div>
      <div class="form-grid">
        <div class="form-row">
          <label>Case</label>
          <select name="caseId" [(ngModel)]="filters.caseId">
            <option value="">All cases</option>
            @for (c of cases(); track c.id) {
              <option [value]="c.id">{{ c.name }} ({{ c.status }})</option>
            }
          </select>
        </div>
        <div class="form-row">
          <label>Actor</label>
          <select name="actor" [(ngModel)]="filters.actor">
            <option value="">All actors</option>
            @for (a of actors(); track a) {
              <option [value]="a">{{ a }}</option>
            }
          </select>
        </div>
        <div class="form-row">
          <label>Action</label>
          <select name="action" [(ngModel)]="filters.action">
            <option value="">All actions</option>
            @for (a of actions; track a) {
              <option [value]="a">{{ a }}</option>
            }
          </select>
        </div>
        <div class="form-row">
          <label>Target type</label>
          <select name="targetType" [(ngModel)]="filters.targetType">
            <option value="">All target types</option>
            @for (t of targetTypes; track t) {
              <option [value]="t">{{ t }}</option>
            }
          </select>
        </div>
        <div class="form-row">
          <label>From</label>
          <input type="datetime-local" name="from" [(ngModel)]="filters.from" />
        </div>
      </div>
      <div class="form-actions">
        @if (filters.caseId) {
          <button type="button" class="secondary sm" (click)="verifyChain()">Verify chain</button>
        }
        <button type="button" class="secondary sm" (click)="clear()">Clear</button>
        <button type="button" class="sm" (click)="applyFilters()">Filter</button>
      </div>
      @if (chain()) {
        <p style="margin-top:0.75rem;margin-bottom:0">
          <span class="badge" [class.badge-success]="chain()!.valid" [class.badge-hold]="!chain()!.valid">
            {{ chain()!.valid ? 'Chain valid ✓' : 'Chain BROKEN ✗' }}
          </span>
          <span class="muted" style="margin-left:0.5rem">{{ chain()!.entries }} entries for case {{ chain()!.caseId }}</span>
        </p>
      }
    </div>

    @if (events().length === 0 && !loading()) {
      <app-empty-state icon="🔍" title="No audit events" hint="Adjust the filters or wait for events to land." />
    } @else {
      <app-paginator
        [page]="page()"
        [size]="size()"
        [total]="total()"
        (pageChange)="goToPage($event)"
        (sizeChange)="changeSize($event)" />
      <div class="timeline">
        @for (e of events();track e.eventId) {
          <div class="event card">
            <div class="row-between">
              <strong>{{ e.action }}</strong>
              <span class="faint">{{ e.occurredAt }}</span>
            </div>
            <div class="kv" style="margin-top:0.5rem">
              <span class="kv-key">Actor</span><span>{{ e.actor }}</span>
              <span class="kv-key">Target</span><span>{{ e.targetType }} {{ e.targetId }}</span>
              @if (e.caseId) { <span class="kv-key">Case</span><span>{{ e.caseId }}</span> }
              @if (e.correlationId) { <span class="kv-key">Correlation</span><span class="faint">{{ e.correlationId }}</span> }
            </div>
            @if (e.after) {
              <details style="margin-top:0.4rem"><summary class="muted" style="cursor:pointer;font-size:0.82rem">before/after</summary>
                <pre style="font-size:0.8rem;background:var(--surface-2);padding:0.5rem;border-radius:4px;overflow:auto">{{ e.after }}</pre>
              </details>
            }
            @if (e.hash) {
              <div class="faint" style="font-size:0.72rem;margin-top:0.3rem">hash: {{ e.hash.slice(0, 16) }}…</div>
            }
          </div>
        }
      </div>
      <app-paginator
        variant="nav"
        [page]="page()"
        [size]="size()"
        [total]="total()"
        (pageChange)="goToPage($event)" />
    }
  `,
})
export class AuditComponent implements OnInit {
  private readonly audit = inject(AuditService);
  private readonly caseService = inject(CaseService);

  /** Static vocabularies from the audit contract — drive the dropdowns. */
  readonly actions = AUDIT_ACTIONS;
  readonly targetTypes = AUDIT_TARGET_TYPES;

  readonly events = signal<AuditEvent[]>([]);
  readonly cases = signal<Case[]>([]);
  /** Distinct actors accumulated across loads so the dropdown never shrinks
   *  when a filter narrows the visible results — the user can always pick any
   *  actor they've seen, even after filtering. */
  readonly actors = signal<string[]>([]);
  readonly chain = signal<ChainVerification | null>(null);
  readonly loading = signal(false);

  /** Server-side paging state (FR-7.4). `total` comes from the X-Total-Count
   *  header on GET /api/audit, so the paginator can show "26–50 of 100". */
  readonly page = signal(0);
  readonly size = signal(25);
  readonly total = signal(0);

  filters = { caseId: '', actor: '', action: '', targetType: '', from: '', to: '' };

  ngOnInit(): void {
    this.loadCases();
    this.load();
  }

  private loadCases(): void {
    this.caseService.list({ size: 200 }).subscribe({
      next: (page) => this.cases.set(page.content ?? []),
      error: () => this.cases.set([]),
    });
  }

  /** Re-runs the filter from page 0 — used by the Filter/Clear buttons, since a
   *  new filter makes the current page number meaningless. */
  applyFilters(): void {
    this.page.set(0);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const f = {
      caseId: this.filters.caseId.trim() || undefined,
      actor: this.filters.actor.trim() || undefined,
      action: this.filters.action.trim() || undefined,
      targetType: this.filters.targetType.trim() || undefined,
      from: this.toIso(this.filters.from),
      to: this.toIso(this.filters.to),
      page: this.page(),
      size: this.size(),
    };
    this.audit.list(f).subscribe({
      next: (res) => {
        const items = res.items ?? [];
        this.events.set(items);
        this.total.set(res.total);
        const seen = items.map((e) => e.actor).filter(Boolean);
        this.actors.update((prev) => [...new Set([...prev, ...seen])].sort());
        this.loading.set(false);
      },
      error: () => { this.events.set([]); this.total.set(0); this.loading.set(false); },
    });
  }

  goToPage(p: number): void {
    this.page.set(p);
    this.load();
  }

  /** A bigger/smaller window invalidates the offset, so restart at page 0. */
  changeSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
    this.load();
  }

  clear(): void {
    this.filters = { caseId: '', actor: '', action: '', targetType: '', from: '', to: '' };
    this.chain.set(null);
    this.applyFilters();
  }

  verifyChain(): void {
    const caseId = this.filters.caseId.trim();
    if (!caseId) return;
    this.audit.verifyChain(caseId).subscribe({
      next: (v) => this.chain.set(v),
      error: () => this.chain.set(null),
    });
  }

  /** datetime-local yields `YYYY-MM-DDTHH:mm[:ss]` with no zone. audit-service
   *  expects an ISO-8601 instant, so we treat the picked wall-clock as UTC and
   *  append `Z` — matching the old free-text behaviour where users typed the
   *  `Z` themselves. A value that already carries a zone passes through. */
  private toIso(value: string): string | undefined {
    const s = value?.trim();
    if (!s) return undefined;
    if (s.endsWith('Z') || /[+-]\d\d:?\d\d$/.test(s)) return s;
    return s.length === 16 ? `${s}:00Z` : `${s}Z`;
  }
}
