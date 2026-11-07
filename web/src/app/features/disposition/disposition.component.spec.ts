import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DispositionComponent } from './disposition.component';
import { DispositionRun } from '../../core/api.model';

/**
 * Component tests for the Disposition history table's rows-per-page selector.
 * Unlike audit/cases this screen pages client-side — GET /runs takes no
 * page/size params and returns every run — so these cover the slice size, page
 * navigation, the reset-to-page-0 rule on a size change, and the clamp that
 * stops a stale page from rendering empty after a poll returns fewer runs.
 */
describe('DispositionComponent history paging', () => {
  let fixture: ComponentFixture<DispositionComponent>;
  let component: DispositionComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DispositionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(DispositionComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function run(i: number): DispositionRun {
    return { id: `run-${i}`, status: 'COMPLETED', deleted: i, skippedOnHold: 0, errors: 0 } as DispositionRun;
  }

  /** Flushes the ngOnInit GET /runs with `count` completed runs. */
  function flushRuns(count: number): void {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/holds/disposition/runs')
      .flush(Array.from({ length: count }, (_, i) => run(i)));
    fixture.detectChanges();
  }

  it('shows only the first page of runs at the default size', () => {
    flushRuns(60);

    expect(component.size()).toBe(25);
    expect(component.pagedRuns().length).toBe(25);
    expect(component.pagedRuns()[0].id).toBe('run-0');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(25);
  });

  /** The `full` paginator above the table carries the selector; the `nav`
   *  footer below it must not render a second one. */
  it('renders exactly one rows-per-page selector, above the table', () => {
    flushRuns(60);

    const selects: NodeListOf<HTMLSelectElement> = fixture.nativeElement.querySelectorAll('.pager select');
    expect(selects.length).toBe(1);
    expect(Array.from(selects[0].options).map((o) => Number(o.value))).toEqual([10, 25, 50, 100]);
  });

  it('slices to the size picked in the selector', () => {
    flushRuns(60);

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('.pager select');
    select.value = '50';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(component.size()).toBe(50);
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(50);
  });

  it('advances a page without refetching', () => {
    flushRuns(60);

    component.goToPage(1);
    fixture.detectChanges();

    expect(component.pagedRuns()[0].id).toBe('run-25');
    expect(component.pagedRuns().length).toBe(25);
  });

  it('restarts at page 0 when the size changes', () => {
    flushRuns(100);
    component.goToPage(3);

    component.changeSize(50);

    expect(component.page()).toBe(0);
    expect(component.pagedRuns()[0].id).toBe('run-0');
    expect(component.pagedRuns().length).toBe(50);
  });

  it('clamps the page when a reload returns fewer runs', () => {
    flushRuns(60);
    component.goToPage(2);

    component.load();
    http.expectOne((r) => r.url === '/api/holds/disposition/runs').flush([run(0)]);
    fixture.detectChanges();

    expect(component.page()).toBe(0);
    expect(component.pagedRuns().length).toBe(1);
  });

  it('hides the paginator when there are no runs', () => {
    flushRuns(0);

    expect(fixture.nativeElement.querySelector('.pager')).toBeNull();
  });
});
