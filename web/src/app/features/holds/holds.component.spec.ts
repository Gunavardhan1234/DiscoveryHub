import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { HoldsComponent } from './holds.component';
import { Hold, HoldStatus } from '../../core/api.model';

/**
 * Component tests for the Holds screen's status filter + rows-per-page selector.
 * GET /api/holds takes only `caseId` and returns a bare array, so the status
 * filter and the paging are both client-side slices — these cover the LIVE
 * grouping (active vs released, the reason the filter exists), the option
 * counts, filter-then-slice ordering, and the page resets/clamps.
 */
describe('HoldsComponent filtering and paging', () => {
  let fixture: ComponentFixture<HoldsComponent>;
  let component: HoldsComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HoldsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(HoldsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function hold(id: string, status: HoldStatus): Hold {
    return {
      holdId: id,
      caseId: 'case-1',
      status,
      custodians: [],
      heldCount: 3,
      appliedCount: 3,
      progressPct: 100,
    } as Hold;
  }

  /** Flushes the three ngOnInit calls: holds, cases, retention policies. */
  function flushInit(holds: Hold[]): void {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/holds' && r.method === 'GET').flush(holds);
    http.expectOne((r) => r.url === '/api/cases')
      .flush({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 100 });
    http.expectOne((r) => r.url === '/api/holds/retention-policy').flush({ policies: [] });
    fixture.detectChanges();
  }

  function mixedHolds(): Hold[] {
    return [
      hold('h-pending', 'PENDING'),
      hold('h-active', 'ACTIVE'),
      hold('h-releasing', 'RELEASING'),
      hold('h-released-1', 'RELEASED'),
      hold('h-released-2', 'RELEASED'),
      hold('h-failed', 'FAILED'),
    ];
  }

  it('shows every hold when no status filter is set', () => {
    flushInit(mixedHolds());

    expect(component.filteredHolds().length).toBe(6);
  });

  it('LIVE groups pending, active and releasing — excluding released and failed', () => {
    flushInit(mixedHolds());

    component.statusFilter = 'LIVE';
    component.onFilterChange();

    expect(component.filteredHolds().map((h) => h.status).sort())
      .toEqual(['ACTIVE', 'PENDING', 'RELEASING']);
  });

  it('separates released holds', () => {
    flushInit(mixedHolds());

    component.statusFilter = 'RELEASED';
    component.onFilterChange();

    expect(component.filteredHolds().length).toBe(2);
    expect(component.filteredHolds().every((h) => h.status === 'RELEASED')).toBeTrue();
  });

  it('filters to a single exact status', () => {
    flushInit(mixedHolds());

    component.statusFilter = 'FAILED';
    component.onFilterChange();

    expect(component.filteredHolds().map((h) => h.holdId)).toEqual(['h-failed']);
  });

  it('counts each status for the dropdown labels', () => {
    flushInit(mixedHolds());

    expect(component.countLive()).toBe(3);
    expect(component.countOf('RELEASED')).toBe(2);
    expect(component.countOf('FAILED')).toBe(1);
    expect(component.countOf('ACTIVE')).toBe(1);
  });

  it('renders one rows-per-page selector and pages the filtered rows', () => {
    flushInit(Array.from({ length: 60 }, (_, i) => hold(`h-${i}`, 'ACTIVE')));

    const selects: NodeListOf<HTMLSelectElement> =
      fixture.nativeElement.querySelectorAll('.pager select');
    expect(selects.length).toBe(1);
    expect(component.size).toBe(25);
    expect(component.pagedHolds().length).toBe(25);

    component.goToPage(2);
    expect(component.pagedHolds().length).toBe(10);
  });

  it('filters before slicing, so a narrow filter fits one page', () => {
    const holds = [
      ...Array.from({ length: 40 }, (_, i) => hold(`h-rel-${i}`, 'RELEASED')),
      hold('h-only-active', 'ACTIVE'),
    ];
    flushInit(holds);

    component.goToPage(1);
    component.statusFilter = 'ACTIVE';
    component.onFilterChange();

    expect(component.page).toBe(0);
    expect(component.pagedHolds().map((h) => h.holdId)).toEqual(['h-only-active']);
  });

  it('restarts at page 0 when the rows-per-page changes', () => {
    flushInit(Array.from({ length: 60 }, (_, i) => hold(`h-${i}`, 'ACTIVE')));
    component.goToPage(2);

    component.changeSize(50);

    expect(component.page).toBe(0);
    expect(component.pagedHolds().length).toBe(50);
  });

  it('clamps the page when a reload returns fewer holds', () => {
    flushInit(Array.from({ length: 60 }, (_, i) => hold(`h-${i}`, 'ACTIVE')));
    component.goToPage(2);

    component.loadHolds();
    http.expectOne((r) => r.url === '/api/holds' && r.method === 'GET').flush([hold('h-0', 'ACTIVE')]);
    fixture.detectChanges();

    expect(component.page).toBe(0);
    expect(component.pagedHolds().length).toBe(1);
  });

  it('reloads server-side when the case filter changes', () => {
    flushInit(mixedHolds());

    component.onCaseChange({ target: { value: 'case-9' } } as unknown as Event);

    const req = http.expectOne((r) => r.url === '/api/holds' && r.params.get('caseId') === 'case-9');
    req.flush([hold('h-9', 'ACTIVE')]);
    expect(component.filterCaseId()).toBe('case-9');
  });

  /** The banner link only changed the URL; a query-param-only navigation reuses
   *  the component, so ngOnInit never re-ran and the list stayed filtered. */
  it('the banner "clear filter" link actually clears the case filter', () => {
    flushInit(mixedHolds());
    component.filterCaseId.set('case-1');
    fixture.detectChanges();

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.muted a');
    expect(link.textContent).toContain('clear filter');
    link.click();

    http.expectOne((r) => r.url === '/api/holds' && !r.params.has('caseId')).flush([]);
    expect(component.filterCaseId()).toBeNull();
  });

  it('distinguishes "no holds at all" from "none match the filter"', () => {
    flushInit(mixedHolds());

    component.statusFilter = 'LIVE';
    component.onFilterChange();
    // Every live hold released in the meantime: filter matches nothing.
    component.statusFilter = 'FAILED';
    component.onFilterChange();
    expect(component.filteredHolds().length).toBe(1);

    component.clearFilters();
    http.expectOne((r) => r.url === '/api/holds' && r.method === 'GET').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.empty h3')?.textContent)
      .toContain('No legal holds');
  });
});
