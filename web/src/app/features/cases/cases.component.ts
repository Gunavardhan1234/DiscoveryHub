import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CaseService } from '../../core/services/case.service';
import { Case, CaseCreate, CaseStatus, MatterType, Page } from '../../core/api.model';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';
import { PaginatorComponent } from '../../shared/paginator/paginator.component';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';

const TRANSITIONS: Record<CaseStatus, CaseStatus[]> = {
  DRAFT: ['ACTIVE'],
  ACTIVE: ['UNDER_REVIEW'],
  UNDER_REVIEW: ['CLOSED'],
  CLOSED: [],
};

/** Cases screen (FR-2): list + create + State-pattern transitions.
 *  CLOSED is terminal & read-only — the transition buttons disappear. */
@Component({
  selector: 'app-cases',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusChipComponent, PaginatorComponent, EmptyStateComponent],
  template: `
    <div class="page-head">
      <h2>Cases</h2>
      <button type="button" class="sm" (click)="toggleCreate()">+ New case</button>
    </div>

    @if (showCreate()) {
      <div class="card" style="margin-bottom:1rem">
        <div class="card-title">Create case</div>
        <div class="form-grid">
          <div class="form-row">
            <label>Name</label>
            <input name="name" [(ngModel)]="draft.name" placeholder="Project Kingfisher" />
          </div>
          <div class="form-row">
            <label>Owner</label>
            <input name="owner" [(ngModel)]="draft.owner" placeholder="investigator@demo" />
          </div>
          <div class="form-row">
            <label>Matter type</label>
            <select name="matterType" [(ngModel)]="draft.matterType">
              <option value="INVESTIGATION">Investigation</option>
              <option value="LITIGATION">Litigation</option>
              <option value="REGULATORY_INQUIRY">Regulatory inquiry</option>
            </select>
          </div>
          <div class="form-row">
            <label>Description</label>
            <input name="description" [(ngModel)]="draft.description" placeholder="Q2 wire fraud review" />
          </div>
        </div>
        <div class="form-actions">
          <button type="button" class="secondary sm" (click)="toggleCreate()">Cancel</button>
          <button type="button" class="sm" [disabled]="!canCreate()" (click)="create()">Create</button>
        </div>
      </div>
    }

    <div class="card" style="margin-bottom:1rem">
      <div class="filters" style="gap:0.6rem">
        <input
          name="caseSearch"
          [(ngModel)]="searchQuery"
          (input)="onFilterChange()"
          placeholder="Search by case name…"
          style="flex:1;min-width:16rem" />
        <select name="statusFilter" [(ngModel)]="statusFilter" (change)="onFilterChange()" style="width:auto">
          <option value="">All statuses</option>
          <option value="DRAFT">Draft</option>
          <option value="ACTIVE">Active</option>
          <option value="UNDER_REVIEW">Under review</option>
          <option value="CLOSED">Closed</option>
        </select>
        <select name="matterFilter" [(ngModel)]="matterFilter" (change)="onFilterChange()" style="width:auto">
          <option value="">All matter types</option>
          <option value="INVESTIGATION">Investigation</option>
          <option value="LITIGATION">Litigation</option>
          <option value="REGULATORY_INQUIRY">Regulatory inquiry</option>
        </select>
        @if (searchQuery || statusFilter || matterFilter) {
          <button type="button" class="ghost sm" (click)="clearFilters()">Clear</button>
        }
      </div>
    </div>

    @if ((page()?.totalElements ?? 0) > 0) {
      <app-paginator
        [page]="currentPage"
        [size]="size"
        [total]="page()?.totalElements ?? 0"
        (pageChange)="onPage($event)"
        (sizeChange)="onSize($event)" />
    }

    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>Name</th><th>Matter</th><th>Owner</th><th>Status</th><th>Version</th><th style="text-align:right">Actions</th></tr>
        </thead>
        <tbody>
          @for (c of filteredCases(); track c.id) {
            <tr>
              <td><a [routerLink]="['/cases', c.id]"><strong>{{ c.name }}</strong></a><br><span class="faint">{{ c.id }}</span></td>
              <td>{{ c.matterType }}</td>
              <td>{{ c.owner }}</td>
              <td><app-status-chip [value]="c.status" /></td>
              <td class="faint">{{ c.version }}</td>
              <td style="text-align:right">
                @for (t of allowed(c.status); track t) {
                  <button type="button" class="ghost sm" (click)="transition(c, t)">→ {{ t }}</button>
                }
                @if (c.status === 'CLOSED') { <span class="faint">read-only</span> }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6"><app-empty-state icon="📁" title="No cases found" hint="Adjust your search or filters, or create a new case." /></td></tr>
          }
        </tbody>
      </table>
    </div>

    @if ((page()?.totalElements ?? 0) > 0) {
      <app-paginator
        variant="nav"
        [page]="currentPage"
        [size]="size"
        [total]="page()?.totalElements ?? 0"
        (pageChange)="onPage($event)" />
    }
  `,
})
export class CasesComponent implements OnInit {
  private readonly cases = inject(CaseService);

  readonly page = signal<Page<Case> | null>(null);
  readonly showCreate = signal(false);
  currentPage = 0;
  size = 25;

  /** Client-side filters: text search + status go to the backend (q, status params);
   *  matter type is filtered client-side on the loaded page. */
  searchQuery = '';
  statusFilter: '' | CaseStatus = '';
  matterFilter: '' | MatterType = '';
  private filterTimer: ReturnType<typeof setTimeout> | null = null;

  draft: CaseCreate = { name: '', description: '', matterType: 'INVESTIGATION', owner: 'investigator@demo' };

  ngOnInit(): void {
    this.load();
  }

  /** Cases after the client-side matter-type filter is applied. */
  filteredCases(): Case[] {
    const content = this.page()?.content ?? [];
    if (!this.matterFilter) return content;
    return content.filter((c) => c.matterType === this.matterFilter);
  }

  load(): void {
    this.cases.list({
      page: this.currentPage,
      size: this.size,
      q: this.searchQuery.trim() || undefined,
      status: this.statusFilter || undefined,
    }).subscribe({
      next: (p) => this.page.set(p),
      error: () => this.page.set({ content: [], totalElements: 0, totalPages: 0, page: this.currentPage, size: this.size }),
    });
  }

  /** Debounced reload on text input; dropdowns fire immediately via (change). */
  onFilterChange(): void {
    if (this.filterTimer) {
      clearTimeout(this.filterTimer);
    }
    this.filterTimer = setTimeout(() => {
      this.currentPage = 0;
      this.load();
    }, 300);
  }

  clearFilters(): void {
    this.searchQuery = '';
    this.statusFilter = '';
    this.matterFilter = '';
    this.currentPage = 0;
    this.load();
  }

  onPage(p: number): void {
    this.currentPage = p;
    this.load();
  }

  /** Rows-per-page change; the old offset no longer lines up, so restart. */
  onSize(size: number): void {
    this.size = size;
    this.currentPage = 0;
    this.load();
  }

  toggleCreate(): void {
    this.showCreate.update((v) => !v);
  }

  canCreate(): boolean {
    return this.draft.name.trim().length > 0 && this.draft.owner.trim().length > 0;
  }

  create(): void {
    this.cases.create(this.draft).subscribe(() => {
      this.draft = { name: '', description: '', matterType: 'INVESTIGATION', owner: 'investigator@demo' };
      this.showCreate.set(false);
      this.currentPage = 0;
      this.load();
    });
  }

  allowed(status: CaseStatus): CaseStatus[] {
    return TRANSITIONS[status] ?? [];
  }

  transition(c: Case, to: CaseStatus): void {
    this.cases.transition(c.id, to).subscribe(() => this.load());
  }
}
