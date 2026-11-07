import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CaseService } from '../../core/services/case.service';
import { MessageService } from '../../core/services/message.service';
import { CaseDetail, CaseUpdate, MatterType, SavedSearch } from '../../core/api.model';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { HoldBadgeComponent } from '../../shared/hold-badge/hold-badge.component';

/**
 * Case Detail screen (FR-2.3/2.4/8.1): the single view of everything scoped
 * to a case — custodians, evidence items, and saved searches — bound from
 * the case-service Facade endpoint (`GET /api/cases/{id}/detail`) in one
 * round-trip. `counts.heldCount` (FR-4.4) is resolved server-side from
 * hold-service via `HoldSummaryPort`; clicking it deep-links to the Holds
 * screen filtered to this case (`?caseId=`). Exports are still only
 * reachable via the Exports screen until export-service grows its own
 * per-case summary endpoint.
 *
 * <p>FR-4.4 also requires hold status on *every message*, not just the case
 * total, so the Evidence tab resolves the hold set for its message ids from
 * archive-service's bulk read (`GET /api/messages?ids=`) and renders it with
 * the shared hold badge. Case-service deliberately does not proxy this: it
 * would mean a per-message cross-service fan-out inside the case Facade, and
 * the hold *set* lives in the archive document that owns deletion protection.
 * The column degrades to blank if archive-service is unreachable (NFR-2).
 */
@Component({
  selector: 'app-case-detail',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusChipComponent, EmptyStateComponent, HoldBadgeComponent],
  template: `
    <p><a routerLink="/cases">← Back to cases</a></p>

    @if (detail(); as c) {
      <div class="page-head">
        <div>
          <h2>{{ c.name }} <app-status-chip [value]="c.status" /></h2>
          <p class="subtitle">{{ c.matterType }} · owner {{ c.owner }} · created {{ c.createdAt }}</p>
          <p class="subtitle">
            <a [routerLink]="['/holds']" [queryParams]="{caseId: c.id}" [title]="'FR-4.4: total items currently held for this case'">
              🛡️ {{ c.counts.heldCount }} item{{ c.counts.heldCount === 1 ? '' : 's' }} held
            </a>
          </p>
        </div>
        <div class="row">
          <button type="button" class="sm secondary" [disabled]="readOnly()" (click)="startEdit()"
                  [title]="readOnly() ? 'Case is closed — read only' : 'Edit case details'">Edit</button>
          <a class="sm secondary" style="display:inline-flex"
             [style.pointer-events]="readOnly() ? 'none' : ''" [style.opacity]="readOnly() ? '0.5' : '1'"
             [routerLink]="['/holds']" [queryParams]="{caseId: c.id}"
             [title]="readOnly() ? 'Case is closed — holds not allowed (FR-2.5)' : 'Place a hold →'">Place a hold →</a>
          <a class="sm" style="display:inline-flex"
             [style.pointer-events]="readOnly() ? 'none' : ''" [style.opacity]="readOnly() ? '0.5' : '1'"
             [routerLink]="['/exports']" [queryParams]="{caseId: c.id}"
             [title]="readOnly() ? 'Case is closed — exports not allowed (FR-2.5)' : 'Request export →'">Request export →</a>
        </div>
      </div>

      @if (c.description) { <p class="muted">{{ c.description }}</p> }

      @if (editing()) {
        <div class="card" style="margin-bottom:1rem">
          <div class="card-title">Edit case</div>
          <div class="form-grid">
            <div class="form-row"><label>Name</label><input name="ename" [(ngModel)]="editName" /></div>
            <div class="form-row"><label>Owner</label><input name="eowner" [(ngModel)]="editOwner" /></div>
            <div class="form-row"><label>Matter type</label>
              <select name="ematter" [(ngModel)]="editMatterType">
                <option value="INVESTIGATION">Investigation</option>
                <option value="LITIGATION">Litigation</option>
                <option value="REGULATORY_INQUIRY">Regulatory inquiry</option>
              </select>
            </div>
            <div class="form-row" style="grid-column:1/-1"><label>Description</label><input name="edesc" [(ngModel)]="editDescription" /></div>
          </div>
          <div class="form-actions">
            <button type="button" class="sm" [disabled]="!editName.trim()" (click)="saveEdit()">Save</button>
            <button type="button" class="sm ghost" (click)="cancelEdit()">Cancel</button>
          </div>
        </div>
      }

      <div class="tabs">
        <button type="button" [class.active]="tab() === 'evidence'" (click)="tab.set('evidence')">Evidence ({{ c.counts.evidenceCount }})</button>
        <button type="button" [class.active]="tab() === 'custodians'" (click)="tab.set('custodians')">Custodians ({{ c.counts.custodianCount }})</button>
        <button type="button" [class.active]="tab() === 'searches'" (click)="tab.set('searches')">Saved searches ({{ c.counts.savedSearchCount }})</button>
      </div>

      @if (tab() === 'evidence') {
        <div class="table-wrap">
          <table>
            <thead><tr><th>Message id</th><th>Hold</th><th>Added by</th><th>Added at</th><th>Source</th><th style="text-align:right">Actions</th></tr></thead>
            <tbody>
              @for (e of c.evidence; track e.id) {
                <tr>
                  <td class="faint">{{ e.messageId }}</td>
                  <td><app-hold-badge [onHold]="holdCount(e.messageId) > 0" [count]="holdCount(e.messageId)" /></td>
                  <td>{{ e.addedBy || '—' }}</td><td class="faint">{{ e.addedAt }}</td><td class="faint">{{ e.source || '—' }}</td>
                  <td style="text-align:right"><button type="button" class="ghost sm danger" [disabled]="readOnly()" (click)="removeEvidence(e.id)"
                          [title]="readOnly() ? 'Case is closed — read only' : 'Remove evidence (EVIDENCE_REMOVED)'">Remove</button></td>
                </tr>
              } @empty {
                <tr><td colspan="6"><app-empty-state icon="🗂️" title="No evidence yet" hint="Add messages from the Search screen." /></td></tr>
              }
            </tbody>
          </table>
        </div>
      }

      @if (tab() === 'custodians') {
        <div class="card" style="margin-bottom:1rem">
          <div class="card-title">Add custodian</div>
          <div class="form-grid">
            <div class="form-row"><label>Custodian id</label><input name="cid" [(ngModel)]="newCustodianId" placeholder="c-0007" /></div>
            <div class="form-row"><label>Name (optional)</label><input name="cname" [(ngModel)]="newCustodianName" placeholder="Ada Byron" /></div>
          </div>
          <div class="form-actions"><button type="button" class="sm" [disabled]="!newCustodianId.trim() || readOnly()" (click)="addCustodian()">Add</button></div>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>Id</th><th>Name</th><th>Added</th><th style="text-align:right">Actions</th></tr></thead>
            <tbody>
              @for (cust of c.custodians; track cust.custodianId) {
                <tr>
                  <td><strong>{{ cust.custodianId }}</strong></td>
                  <td>{{ cust.custodianName || '—' }}</td>
                  <td class="faint">{{ cust.addedAt }}</td>
                  <td style="text-align:right"><button type="button" class="ghost sm danger" [disabled]="readOnly()" (click)="removeCustodian(cust.custodianId)">Remove</button></td>
                </tr>
              } @empty {
                <tr><td colspan="4"><app-empty-state icon="👤" title="No custodians attached" hint="Attach the employees in scope for this matter." /></td></tr>
              }
            </tbody>
          </table>
        </div>
      }

      @if (tab() === 'searches') {
        <div class="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Spec</th><th>Saved</th><th style="text-align:right">Actions</th></tr></thead>
            <tbody>
              @for (s of c.savedSearches; track s.id) {
                <tr>
                  <td><strong>{{ s.name }}</strong></td>
                  <td class="faint" style="max-width:22rem;overflow:hidden;text-overflow:ellipsis">{{ s.spec }}</td>
                  <td class="faint">{{ s.createdAt }}</td>
                  <td style="text-align:right">
                    <button type="button" class="ghost sm" (click)="rerunSearch(s)">Re-run</button>
                    <button type="button" class="ghost sm danger" [disabled]="readOnly()" (click)="deleteSearch(s.id)"
                            [title]="readOnly() ? 'Case is closed — read only' : 'Delete saved search (SAVED_SEARCH_DELETED)'">Delete</button>
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="4"><app-empty-state icon="🔎" title="No saved searches" hint="Save a search from the Search screen to re-run it later." /></td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    } @else if (loading()) {
      <p class="muted">Loading case…</p>
    } @else {
      <app-empty-state icon="⚠️" title="Case not found" hint="It may have been removed, or the case-service is unreachable." />
    }
  `,
})
export class CaseDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly cases = inject(CaseService);
  private readonly messages = inject(MessageService);

  readonly detail = signal<CaseDetail | null>(null);
  /** messageId -> size of that message's hold set (FR-4.4). Absent/0 means not
   *  held, which is also what an unreachable archive-service yields. */
  readonly holdCounts = signal<Record<string, number>>({});
  readonly loading = signal(true);
  readonly tab = signal<'evidence' | 'custodians' | 'searches'>('evidence');
  readonly editing = signal(false);

  newCustodianId = '';
  newCustodianName = '';

  /** Inline edit-case draft (FR-2.3 / FR-7.1 CASE_UPDATED). */
  editName = '';
  editDescription = '';
  editOwner = '';
  editMatterType: MatterType = 'INVESTIGATION';

  private caseId = '';

  ngOnInit(): void {
    this.caseId = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  readOnly(): boolean {
    return this.detail()?.status === 'CLOSED';
  }

  load(): void {
    this.loading.set(true);
    this.cases.detail(this.caseId).subscribe({
      next: (d) => {
        this.detail.set(d);
        this.loading.set(false);
        this.loadHoldStatus(d);
      },
      error: () => { this.detail.set(null); this.loading.set(false); },
    });
  }

  /** Resolves the hold set for every evidence message in one bulk archive read
   *  (FR-4.4). Deliberately fired after the detail render rather than awaited,
   *  so the tab paints immediately and the badges fill in. */
  private loadHoldStatus(detail: CaseDetail): void {
    const ids = (detail.evidence ?? []).map((e) => e.messageId);
    if (ids.length === 0) {
      this.holdCounts.set({});
      return;
    }
    this.messages.byIds(ids).subscribe((messages) => {
      const counts: Record<string, number> = {};
      for (const m of messages) {
        // The hold set is authoritative; onHold is the denormalized mirror, so
        // prefer the set's size and fall back to the flag as a count of 1.
        counts[m.id] = m.holds?.length ?? (m.onHold ? 1 : 0);
      }
      this.holdCounts.set(counts);
    });
  }

  holdCount(messageId: string): number {
    return this.holdCounts()[messageId] ?? 0;
  }

  addCustodian(): void {
    const id = this.newCustodianId.trim();
    if (!id) return;
    this.cases.addCustodian(this.caseId, { custodianId: id, custodianName: this.newCustodianName.trim() || undefined })
      .subscribe(() => {
        this.newCustodianId = '';
        this.newCustodianName = '';
        this.load();
      });
  }

  removeCustodian(custodianId: string): void {
    this.cases.removeCustodian(this.caseId, custodianId).subscribe(() => this.load());
  }

  /** Open the inline edit form seeded with the current case values. */
  startEdit(): void {
    const c = this.detail();
    if (!c || this.readOnly()) return;
    this.editName = c.name;
    this.editDescription = c.description ?? '';
    this.editOwner = c.owner;
    this.editMatterType = c.matterType;
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
  }

  /** PATCH-style update — only sends changed fields (FR-2.3 / FR-7.1). A CLOSED
   *  case 409s with CASE_CLOSED (FR-2.5); the error toast surfaces the detail. */
  saveEdit(): void {
    const c = this.detail();
    if (!c) return;
    const body: CaseUpdate = {};
    if (this.editName.trim() && this.editName.trim() !== c.name) body.name = this.editName.trim();
    if (this.editDescription !== (c.description ?? '')) body.description = this.editDescription;
    if (this.editOwner.trim() && this.editOwner.trim() !== c.owner) body.owner = this.editOwner.trim();
    if (this.editMatterType !== c.matterType) body.matterType = this.editMatterType;
    this.cases.update(this.caseId, body).subscribe(() => {
      this.editing.set(false);
      this.load();
    });
  }

  /** Remove a single evidence item (FR-7.1 EVIDENCE_REMOVED). A CLOSED case
   *  409s with CASE_CLOSED (FR-2.5) via the @ClosedCaseGuard. */
  removeEvidence(evidenceId: string): void {
    this.cases.removeEvidence(this.caseId, evidenceId).subscribe(() => this.load());
  }

  deleteSearch(searchId: string): void {
    this.cases.deleteSavedSearch(this.caseId, searchId).subscribe(() => this.load());
  }

  /** Re-run a saved search: hand its spec to the Search screen via ?spec= (FR-3.5). */
  rerunSearch(s: SavedSearch): void {
    this.router.navigate(['/search'], { queryParams: { caseId: this.caseId, spec: s.spec } });
  }
}
