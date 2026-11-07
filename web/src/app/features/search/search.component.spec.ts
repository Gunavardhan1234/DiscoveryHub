import { ComponentFixture, TestBed, fakeAsync, tick, flush } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { SearchComponent } from './search.component';
import { SearchResult } from '../../core/search.model';

/**
 * Component-level tests for the search screen's autocomplete dropdown (FR-3.1).
 * Verifies the debounce, ≥2-char guard, loading state, suggestion rendering, the
 * "no matches" empty state, graceful error handling, and click-to-select behaviour
 * — all backed by the existing POST /api/search endpoint.
 */
describe('SearchComponent autocomplete', () => {
  let fixture: ComponentFixture<SearchComponent>;
  let component: SearchComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SearchComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SearchComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** ngOnInit fires two HTTP calls: GET /api/cases and POST /api/search/scope-count. */
  function flushInitialRequests(): void {
    http.expectOne((r) => r.url === '/api/cases').flush({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 100 });
    http.expectOne((r) => r.url === '/api/search/scope-count').flush({ count: 0 });
  }

  it('creates and initialises', () => {
    fixture.detectChanges();
    flushInitialRequests();
    expect(component).toBeTruthy();
  });

  it('does not open the dropdown for queries shorter than 2 chars', () => {
    fixture.detectChanges();
    flushInitialRequests();

    component.q = 'a';
    component.scheduleSuggest();

    expect(component.showSuggestions()).toBeFalse();
    expect(component.suggestions().length).toBe(0);
  });

  it('fetches autocomplete hits via POST /api/search after the 300ms debounce', fakeAsync(() => {
    fixture.detectChanges();
    flushInitialRequests();

    component.q = 'wire';
    component.scheduleSuggest();

    // The dropdown opens immediately with a loading state.
    expect(component.suggestionsLoading()).toBeTrue();
    expect(component.showSuggestions()).toBeTrue();

    tick(300);

    const req = http.expectOne((r) => r.url === '/api/search');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.q).toBe('wire');
    expect(req.request.body.size).toBe(5);
    expect(req.request.body.sort).toBe('RELEVANCE');

    const result: SearchResult = {
      total: 1, page: 0, size: 5,
      hits: [{
        messageId: 'm-1', subject: 'Q2 wire transfer',
        snippet: 'Please confirm the <mark>wire</mark> routing',
        score: 1.0, custodianId: 'c-0007', timestamp: '2026-03-14T09:12:00Z',
        type: 'EMAIL', hasAttachment: false, onHold: false,
      }],
    };
    req.flush(result);
    flush();

    expect(component.suggestionsLoading()).toBeFalse();
    expect(component.suggestions().length).toBe(1);
    expect(component.suggestions()[0].subject).toBe('Q2 wire transfer');
  }));

  it('shows the dropdown with a "No matches" empty state when results are empty', fakeAsync(() => {
    fixture.detectChanges();
    flushInitialRequests();

    component.q = 'zzz';
    component.scheduleSuggest();
    tick(300);

    http.expectOne((r) => r.url === '/api/search').flush({ total: 0, page: 0, size: 5, hits: [] });
    flush();

    expect(component.suggestions().length).toBe(0);
    expect(component.showSuggestions()).toBeTrue();
  }));

  it('hides the dropdown on API error without breaking the page', fakeAsync(() => {
    fixture.detectChanges();
    flushInitialRequests();

    component.q = 'wire';
    component.scheduleSuggest();
    tick(300);

    http.expectOne((r) => r.url === '/api/search')
      .flush('error', { status: 500, statusText: 'Server Error' });
    flush();

    expect(component.suggestions().length).toBe(0);
    expect(component.showSuggestions()).toBeFalse();
  }));

  it('populates the search box and runs the full search when a suggestion is clicked', fakeAsync(() => {
    fixture.detectChanges();
    flushInitialRequests();

    component.selectSuggestion('Q2 wire transfer');

    expect(component.q).toBe('Q2 wire transfer');
    expect(component.showSuggestions()).toBeFalse();

    // selectSuggestion → search() → POST /api/search (the full search)
    const searchReq = http.expectOne((r) => r.url === '/api/search' && r.method === 'POST');
    searchReq.flush({ total: 1, page: 0, size: 25, hits: [] });
    flush();

    // search() success → loadFacets() → GET /api/search/facets
    http.expectOne((r) => r.url === '/api/search/facets').flush({
      total: 0, byType: {}, byCustodian: {}, onHoldCount: 0, hasAttachmentCount: 0,
    });
    flush();

    // selectSuggestion → scheduleScopeCount() → fires after 350ms debounce
    tick(350);
    http.expectOne((r) => r.url === '/api/search/scope-count').flush({ count: 1 });
    flush();
  }));
});

/**
 * Pagination (FR-3.4): the rows-per-page selector must actually re-run the
 * query, page navigation must keep the offset, and a brand-new query must
 * restart at page 0 rather than asking for an offset the new result set may
 * not have.
 */
describe('SearchComponent pagination', () => {
  let fixture: ComponentFixture<SearchComponent>;
  let component: SearchComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SearchComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SearchComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/cases').flush({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 100 });
    http.expectOne((r) => r.url === '/api/search/scope-count').flush({ count: 0 });
  });

  afterEach(() => http.verify());

  /** Answer a POST /api/search plus the GET /api/search/facets it triggers. */
  function flushSearch(total = 100): { page: number; size: number } {
    const req = http.expectOne((r) => r.url === '/api/search' && r.method === 'POST');
    const { page, size } = req.request.body;
    req.flush({ total, page, size, hits: [] });
    http.expectOne((r) => r.url === '/api/search/facets').flush({
      total, byType: {}, byCustodian: {}, onHoldCount: 0, hasAttachmentCount: 0,
    });
    return { page, size };
  }

  it('re-runs the query with the new page size, from page 0', fakeAsync(() => {
    component.goToPage(3);
    expect(flushSearch().page).toBe(3);

    component.changeSize(50);
    const spec = flushSearch();

    expect(spec.size).toBe(50);
    expect(spec.page).toBe(0);
    expect(component.size).toBe(50);
    expect(component.page).toBe(0);
    flush();
  }));

  it('keeps the requested offset when navigating pages', fakeAsync(() => {
    component.goToPage(2);
    const spec = flushSearch();

    expect(spec.page).toBe(2);
    expect(spec.size).toBe(25);
    flush();
  }));

  it('restarts at page 0 for a new query', fakeAsync(() => {
    component.goToPage(4);
    flushSearch();

    component.q = 'wire';
    component.search();

    expect(flushSearch().page).toBe(0);
    flush();
  }));
});
