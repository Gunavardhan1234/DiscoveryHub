import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { SearchService } from '../../core/services/search.service';
import { CaseService } from '../../core/services/case.service';
import { FacetResult, QuerySpec, SearchHit, SearchResult, Sort } from '../../core/search.model';
import { Case } from '../../core/api.model';
import { MarkSnippetPipe } from '../../shared/pipes/mark-snippet.pipe';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { PaginatorComponent } from '../../shared/paginator/paginator.component';

/**
 * Search screen (FR-3). Calls {@code POST /api/search} via SearchService, renders
 * highlighted snippets, structured filters, sort, and pagination. Errors are shown
 * by the shared toast; the global spinner covers in-flight requests.
 *
 * <p>Integrates the full search-service read API:
 * <ul>
 *   <li>{@code POST /api/search} — paged, highlighted hits (FR-3.1–FR-3.4).</li>
 *   <li>{@code POST /api/search/scope-count} — a live, debounced match count shown
 *       as filters are adjusted, before the investigator commits to a full search.</li>
 *   <li>{@code POST /api/search/scope-ids} — streams every matching id so the
 *       <em>entire</em> result set (not just the current page) can be added to a
 *       case as evidence (FR-4 / FR-3.6).</li>
 *   <li>{@code GET /api/search/facets} — a scope overview for the text query.</li>
 * </ul>
 * Saved searches (FR-3.5) are persisted per case through the case-service and
 * re-run from the Case Detail screen via the {@code ?spec=} query parameter.
 */
@Component({
  selector: 'app-search',
  standalone: true,
  imports: [FormsModule, MarkSnippetPipe, EmptyStateComponent, PaginatorComponent],
  template: `
    <h2>Search</h2>
    <form (ngSubmit)="search(); hideSuggestions()" class="search-form">
      <div class="search-box-wrap">
        <input
          name="q"
          [(ngModel)]="q"
          (input)="scheduleScopeCount(); scheduleSuggest()"
          (focus)="onInputFocus()"
          (blur)="onInputBlur()"
          (keydown)="onSuggestKeydown($event)"
          placeholder="Search subject, body, participants…"
          class="search-input"
          autocomplete="off" />
        @if (showSuggestions()) {
          <ul class="suggest-dropdown" role="listbox">
            @if (suggestionsLoading()) {
              <li class="suggest-loading" aria-busy="true" aria-live="polite">
                <span class="suggest-spinner"></span> Searching…
              </li>
            } @else if (suggestions().length === 0) {
              <li class="suggest-empty">No matches for "{{ q }}"</li>
            } @else {
              @for (s of suggestions(); track s.messageId; let i = $index) {
                <li role="option" [class.active]="i === activeSuggestion()" (mousedown)="selectSuggestion(s.subject)">
                  <div class="suggest-row">
                    <span class="suggest-subject">{{ s.subject || '(no subject)' }}</span>
                    <span class="suggest-meta">
                      {{ s.type }} · {{ s.custodianId }} · {{ s.timestamp }}
                      @if (s.onHold) { <span class="badge badge-hold">HOLD</span> }
                      @if (s.hasAttachment) { <span class="badge">ATT</span> }
                    </span>
                    @if (s.snippet) { <span class="suggest-snippet" [innerHTML]="s.snippet | markSnippet"></span> }
                  </div>
                </li>
              }
            }
          </ul>
        }
      </div>
      <div class="filters" (input)="scheduleScopeCount()" (change)="scheduleScopeCount()">
        <label><input type="checkbox" name="typeEmail" [(ngModel)]="typeEmail" /> Email</label>
        <label><input type="checkbox" name="typeChat" [(ngModel)]="typeChat" /> Chat</label>
        <input name="custodians" [(ngModel)]="custodians" placeholder="custodians (comma-separated)" />
        <select name="hasAttachment" [(ngModel)]="hasAttachment">
          <option value="">Attachment: any</option>
          <option value="true">Has attachment</option>
          <option value="false">No attachment</option>
        </select>
        <select name="onHold" [(ngModel)]="onHold">
          <option value="">Hold: any</option>
          <option value="true">On hold</option>
          <option value="false">Not on hold</option>
        </select>
        <label class="date-range">From <input type="date" name="fromDate" [(ngModel)]="fromDate" /></label>
        <label class="date-range">To <input type="date" name="toDate" [(ngModel)]="toDate" /></label>
        <select name="sort" [(ngModel)]="sort">
          <option value="RELEVANCE">Sort: relevance</option>
          <option value="DATE_DESC">Newest first</option>
          <option value="DATE_ASC">Oldest first</option>
        </select>
        <button type="submit" [disabled]="loading()">Search</button>
      </div>
      @if (scopeCountLoading()) {
        <p class="muted scope-count" style="margin:0">counting matches…</p>
      } @else if (scopeCount() !== null) {
        <p class="muted scope-count" style="margin:0">≈ {{ scopeCount() }} message(s) match the current filters</p>
      }
    </form>

    @if (facets(); as f) {
      <div class="facets">
        <span class="facets-title">Scope overview for "{{ q || 'all messages' }}"</span>
        <span class="muted facets-note">by search term — not affected by the filters above</span>
        <div class="facets-row">
          <span class="facet"><strong>{{ f.total }}</strong> total</span>
          @for (entry of facetEntries(f.byType); track entry.key) {
            <span class="facet">{{ entry.key }}: <strong>{{ entry.value }}</strong></span>
          }
          <span class="facet">on hold: <strong>{{ f.onHoldCount }}</strong></span>
          <span class="facet">with attachment: <strong>{{ f.hasAttachmentCount }}</strong></span>
        </div>
        @if (facetEntries(f.byCustodian).length > 0) {
          <div class="facets-row">
            <span class="muted">Top custodians:</span>
            @for (entry of facetEntries(f.byCustodian); track entry.key) {
              <span class="facet">{{ entry.key }} ({{ entry.value }})</span>
            }
          </div>
        }
      </div>
    }

    @if (results(); as r) {
      <div class="row-between">
        <p class="muted" style="margin:0">
          {{ r.total }}{{ capped(r) ? '+' : '' }} result(s)
          @if (capped(r)) {
            <span class="faint">— paging covers the first {{ ES_RESULT_WINDOW }}; narrow the filters to reach the rest</span>
          }
        </p>
        @if (r.hits.length > 0) {
          <div class="row">
            <select name="addCase" [(ngModel)]="targetCaseId" style="width:auto">
              <option value="" disabled>Add to case…</option>
              @if (cases().length === 0) {
                <option value="" disabled>No cases available</option>
              } @else {
                @for (c of cases();track c.id) {
                  <option [value]="c.id" [disabled]="c.status === 'CLOSED'">{{ c.name }} ({{ c.status }})</option>
                }
              }
            </select>
            <button type="button" class="sm secondary" [disabled]="!targetCaseId || addingToCase()" (click)="addPageToCase(r)">Add this page to case</button>
            <button type="button" class="sm secondary" [disabled]="!targetCaseId || addingToCase()" (click)="addAllMatchingToCase(r)">Add all {{ r.total }} matching to case</button>
          </div>
        }
      </div>
      @if (targetCaseId) {
        <div class="row save-row">
          <input name="searchName" [(ngModel)]="newSearchName" placeholder="Name this search" style="width:auto" />
          <button type="button" class="sm secondary" [disabled]="!newSearchName.trim() || savingSearch()" (click)="saveSearch()">Save search to case</button>
        </div>
      }
      @if (addResultMessage()) { <p class="muted" style="margin-top:0.4rem">{{ addResultMessage() }}</p> }
      @if (r.hits.length === 0) {
        <app-empty-state icon="📭" title="No matches" hint="Try a broader term or relax the filters." />
      } @else {
        <app-paginator
          [page]="page"
          [size]="size"
          [total]="r.total"
          (pageChange)="goToPage($event)"
          (sizeChange)="changeSize($event)" />
        <ul class="hit-list">
          @for (hit of r.hits; track hit.messageId) {
            <li class="hit">
              <div class="hit-row">
                <span class="hit-subject">{{ hit.subject }}</span>
                <span class="hit-meta">
                  {{ hit.type }} · {{ hit.custodianId }} · {{ hit.timestamp }} · score {{ hit.score.toFixed(2) }}
                </span>
                @if (hit.onHold) { <span class="badge badge-hold">ON HOLD</span> }
                @if (hit.hasAttachment) { <span class="badge">ATT</span> }
              </div>
              <p class="hit-snippet" [innerHTML]="hit.snippet | markSnippet"></p>
            </li>
          }
        </ul>
        <app-paginator
          variant="nav"
          [page]="page"
          [size]="size"
          [total]="r.total"
          (pageChange)="goToPage($event)" />
      }
    } @else if (loading()) {
      <p class="muted">Searching…</p>
    } @else {
      <app-empty-state
        icon="🔎"
        title="Search the corpus"
        hint="Enter a term above and hit Search, or refine with the filters. Results appear here with highlighted matches." />
    }
  `,
})
export class SearchComponent implements OnInit, OnDestroy {
  private readonly searchService = inject(SearchService);
  private readonly caseService = inject(CaseService);
  private readonly route = inject(ActivatedRoute);

  readonly cases = signal<Case[]>([]);
  targetCaseId = '';
  readonly addingToCase = signal(false);
  readonly addResultMessage = signal('');

  q = '';
  typeEmail = false;
  typeChat = false;
  custodians = '';
  hasAttachment: '' | 'true' | 'false' = '';
  onHold: '' | 'true' | 'false' = '';
  fromDate = '';
  toDate = '';
  sort: Sort = 'RELEVANCE';
  size = 25;
  page = 0;

  /** Name field for the "save this search to a case" action (FR-3.5). */
  newSearchName = '';
  readonly savingSearch = signal(false);

  readonly results = signal<SearchResult | null>(null);
  readonly loading = signal(false);

  /** Live scope count (POST /api/search/scope-count), debounced as filters change. */
  readonly scopeCount = signal<number | null>(null);
  readonly scopeCountLoading = signal(false);
  private scopeCountTimer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;

  /** Scope overview (GET /api/search/facets) for the text query. */
  readonly facets = signal<FacetResult | null>(null);

  /** Autocomplete: reuses the existing POST /api/search with a small page size to
   *  fetch the top relevant, highlighted hits for the typed query. Suggestions are
   *  full SearchHit objects (subject + highlighted snippet + meta + badges). */
  readonly suggestions = signal<SearchHit[]>([]);
  readonly suggestionsLoading = signal(false);
  readonly showSuggestions = signal(false);
  readonly activeSuggestion = signal(-1);
  private suggestTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.caseService.list({ size: 100 }).subscribe({
      next: (p) => this.cases.set(p.content ?? []),
      error: () => this.cases.set([]),
    });
    const caseId = this.route.snapshot.queryParamMap.get('caseId');
    if (caseId) {
      this.targetCaseId = caseId;
    }
    // Re-run a saved search: the Case Detail screen navigates here with ?spec=<json>.
    const specParam = this.route.snapshot.queryParamMap.get('spec');
    if (specParam) {
      try {
        this.applySpec(JSON.parse(specParam) as QuerySpec);
        // runSearch, not search: the saved spec's own page/size must survive.
        this.runSearch();
      } catch {
        // Malformed spec — ignore and start from a blank form.
      }
    }
    this.updateScopeCount();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.scopeCountTimer) {
      clearTimeout(this.scopeCountTimer);
    }
    if (this.suggestTimer) {
      clearTimeout(this.suggestTimer);
    }
  }

  /** Debounced autocomplete (FR-3.1). Reuses the existing POST /api/search with a
   *  small page size to fetch the top relevant, highlighted hits. Only fires once
   *  typing pauses (300ms) and the query is at least 2 chars; the backend
   *  full-text matching is case-insensitive and relevance-scored. */
  scheduleSuggest(): void {
    if (this.suggestTimer) {
      clearTimeout(this.suggestTimer);
    }
    const query = this.q.trim();
    if (query.length < 2) {
      this.suggestions.set([]);
      this.suggestionsLoading.set(false);
      this.showSuggestions.set(false);
      this.activeSuggestion.set(-1);
      return;
    }
    // Open the dropdown immediately so the loading state is visible, then fetch.
    this.showSuggestions.set(true);
    this.suggestionsLoading.set(true);
    this.suggestTimer = setTimeout(() => {
      if (this.destroyed) return;
      this.searchService
        .search({ q: query, filters: {}, sort: 'RELEVANCE', page: 0, size: 5 })
        .subscribe({
          next: (res) => {
            if (this.destroyed) return;
            this.suggestions.set(res.hits ?? []);
            this.suggestionsLoading.set(false);
            this.activeSuggestion.set(-1);
            this.showSuggestions.set(true);
          },
          error: () => {
            if (this.destroyed) return;
            this.suggestions.set([]);
            this.suggestionsLoading.set(false);
            this.showSuggestions.set(false);
          },
        });
    }, 300);
  }

  selectSuggestion(s: string): void {
    this.q = s;
    this.hideSuggestions();
    this.scheduleScopeCount();
    this.search();
  }

  onInputFocus(): void {
    if (this.suggestions().length > 0 || this.suggestionsLoading()) {
      this.showSuggestions.set(true);
    }
  }

  /** Delay hiding so a mousedown on a suggestion registers before the blur. */
  onInputBlur(): void {
    setTimeout(() => this.hideSuggestions(), 150);
  }

  hideSuggestions(): void {
    this.showSuggestions.set(false);
    this.activeSuggestion.set(-1);
  }

  onSuggestKeydown(event: KeyboardEvent): void {
    const list = this.suggestions();
    if (!this.showSuggestions() || list.length === 0 || this.suggestionsLoading()) {
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.activeSuggestion.set((this.activeSuggestion() + 1) % list.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.activeSuggestion.set((this.activeSuggestion() - 1 + list.length) % list.length);
    } else if (event.key === 'Enter' && this.activeSuggestion() >= 0) {
      event.preventDefault();
      this.selectSuggestion(list[this.activeSuggestion()].subject);
    } else if (event.key === 'Escape') {
      this.hideSuggestions();
    }
  }

  addPageToCase(r: SearchResult): void {
    if (!this.targetCaseId || r.hits.length === 0) return;
    this.addingToCase.set(true);
    this.addResultMessage.set('');
    const messageIds = r.hits.map((h) => h.messageId);
    this.caseService.addEvidenceBulk(this.targetCaseId, messageIds, 'search-page').subscribe({
      next: (res) => {
        this.addingToCase.set(false);
        this.addResultMessage.set(`Added ${res.added} message(s) to case${res.skipped ? `, ${res.skipped} already present` : ''}.`);
      },
      error: () => {
        this.addingToCase.set(false);
        this.addResultMessage.set('Could not add to case — see the error toast for details.');
      },
    });
  }

  /** Add the ENTIRE result set (across all pages) to a case via scope-ids (FR-4). */
  addAllMatchingToCase(r: SearchResult): void {
    if (!this.targetCaseId || r.total === 0) return;
    this.addingToCase.set(true);
    this.addResultMessage.set('');
    this.searchService.scopeIds(this.buildSpec()).subscribe({
      next: (res) => {
        const ids = res.ids ?? [];
        if (ids.length === 0) {
          this.addingToCase.set(false);
          this.addResultMessage.set('No matching message ids returned.');
          return;
        }
        this.caseService.addEvidenceBulk(this.targetCaseId, ids, 'search-scope').subscribe({
          next: (bulk) => {
            this.addingToCase.set(false);
            this.addResultMessage.set(`Added ${bulk.added} of ${ids.length} matching message(s) to case${bulk.skipped ? `, ${bulk.skipped} already present` : ''}.`);
          },
          error: () => {
            this.addingToCase.set(false);
            this.addResultMessage.set('Could not add to case — see the error toast for details.');
          },
        });
      },
      error: () => {
        this.addingToCase.set(false);
        this.addResultMessage.set('Could not retrieve matching message ids — see the error toast for details.');
      },
    });
  }

  /** Persist the current query+filters to a case as a saved search (FR-3.5). */
  saveSearch(): void {
    const name = this.newSearchName.trim();
    if (!this.targetCaseId || !name) return;
    this.savingSearch.set(true);
    this.addResultMessage.set('');
    this.caseService.saveSearch(this.targetCaseId, name, JSON.stringify(this.buildSpec())).subscribe({
      next: () => {
        this.savingSearch.set(false);
        this.addResultMessage.set(`Saved search "${name}" to the case. Re-run it from the Case Detail → Saved searches tab.`);
        this.newSearchName = '';
      },
      error: () => {
        this.savingSearch.set(false);
        this.addResultMessage.set('Could not save the search — see the error toast for details.');
      },
    });
  }

  /**
   * Entry point for a *new* query: submitting the form, picking a suggestion or
   * changing a filter restarts at page 0. Keeping the old page number would ask
   * the backend for an offset the new result set may not even have, which looks
   * like "pagination is broken" (an empty page with hits clearly available).
   */
  search(): void {
    this.page = 0;
    this.runSearch();
  }

  /** Fetch the current page/size without touching the paging state. */
  private runSearch(): void {
    this.loading.set(true);
    this.searchService.search(this.buildSpec()).subscribe({
      next: (r) => {
        this.results.set(r);
        this.loading.set(false);
        this.loadFacets(this.q);
      },
      error: () => {
        this.loading.set(false);
        // The shared error toast (errorInterceptor) already surfaces the failure.
      },
    });
  }

  /** Debounced live count so the investigator sees the scope size before searching. */
  scheduleScopeCount(): void {
    if (this.scopeCountTimer) {
      clearTimeout(this.scopeCountTimer);
    }
    this.scopeCountTimer = setTimeout(() => this.updateScopeCount(), 350);
  }

  private updateScopeCount(): void {
    if (this.destroyed) return;
    this.scopeCountLoading.set(true);
    this.searchService.count(this.buildSpec()).subscribe({
      next: (c) => {
        if (this.destroyed) return;
        this.scopeCount.set(c.count);
        this.scopeCountLoading.set(false);
      },
      error: () => {
        if (this.destroyed) return;
        this.scopeCount.set(null);
        this.scopeCountLoading.set(false);
      },
    });
  }

  private loadFacets(q: string): void {
    this.searchService.facets(q).subscribe({
      next: (f) => this.facets.set(f),
      error: () => this.facets.set(null),
    });
  }

  /** Flatten a facet map into a trackable array for @for. */
  facetEntries(map: Record<string, number> | undefined): { key: string; value: number }[] {
    if (!map) return [];
    return Object.entries(map).map(([key, value]) => ({ key, value }));
  }

  /**
   * Elasticsearch reports at most 10,000 hits (`track_total_hits`) and refuses
   * a `from + size` window past the same figure, so `total` is a floor once it
   * reaches the cap — and the paginator's last page lands exactly on the window
   * edge, never past it. The live scope count above the results is exact (it
   * comes from the ES count API), so both numbers are shown.
   */
  readonly ES_RESULT_WINDOW = 10_000;

  capped(r: SearchResult): boolean {
    return r.total >= this.ES_RESULT_WINDOW;
  }

  goToPage(p: number): void {
    this.page = p;
    this.runSearch();
  }

  /** Rows-per-page change — re-runs the query (the old behaviour only updated
   *  the scope count, so the selector appeared to do nothing) from page 0,
   *  since the previous offset no longer points at the same window. */
  changeSize(size: number): void {
    this.size = size;
    this.page = 0;
    this.runSearch();
  }

  /** Populate the form from a saved-search spec, then the caller runs search(). */
  private applySpec(spec: QuerySpec): void {
    this.q = spec.q ?? '';
    this.page = spec.page ?? 0;
    this.size = spec.size ?? 25;
    this.sort = (spec.sort as Sort) ?? 'RELEVANCE';
    const f = spec.filters ?? {};
    const types = f.types ?? [];
    this.typeEmail = types.includes('EMAIL');
    this.typeChat = types.includes('CHAT');
    this.custodians = (f.custodians ?? []).join(', ');
    this.fromDate = f.from ?? '';
    this.toDate = f.to ?? '';
    this.hasAttachment = f.hasAttachment == null ? '' : f.hasAttachment ? 'true' : 'false';
    this.onHold = f.onHold == null ? '' : f.onHold ? 'true' : 'false';
    if (spec.caseId) {
      this.targetCaseId = spec.caseId;
    }
  }

  private buildSpec(): QuerySpec {
    const types: string[] = [];
    if (this.typeEmail) {
      types.push('EMAIL');
    }
    if (this.typeChat) {
      types.push('CHAT');
    }
    const custodians = this.custodians.trim()
      ? this.custodians.split(',').map((s) => s.trim()).filter((s) => s.length > 0)
      : undefined;
    return {
      q: this.q,
      filters: {
        types: types.length > 0 ? types : undefined,
        custodians,
        from: this.fromDate || undefined,
        to: this.toDate || undefined,
        hasAttachment: this.hasAttachment === '' ? undefined : this.hasAttachment === 'true',
        onHold: this.onHold === '' ? undefined : this.onHold === 'true',
      },
      sort: this.sort,
      page: this.page,
      size: this.size,
    };
  }
}
