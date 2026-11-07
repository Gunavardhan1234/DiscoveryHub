import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PaginatorComponent } from './paginator.component';

/** The shared paginator drives every paged screen: page count, the
 *  "Showing x–y of N" range label, and the rows-per-page selector. */
describe('PaginatorComponent', () => {
  let fixture: ComponentFixture<PaginatorComponent>;
  let component: PaginatorComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [PaginatorComponent] }).compileComponents();
    fixture = TestBed.createComponent(PaginatorComponent);
    component = fixture.componentInstance;
  });

  it('reports the page count for the current size', () => {
    component.total = 100;
    component.size = 25;
    expect(component.totalPages()).toBe(4);

    component.size = 10;
    expect(component.totalPages()).toBe(10);
  });

  it('labels the visible range out of the total', () => {
    component.total = 100;
    component.size = 25;
    component.page = 0;
    expect(component.rangeLabel()).toBe('Showing 1–25 of 100');

    component.page = 3;
    expect(component.rangeLabel()).toBe('Showing 76–100 of 100');
  });

  it('clamps the last page label to the total', () => {
    component.total = 42;
    component.size = 25;
    component.page = 1;
    expect(component.rangeLabel()).toBe('Showing 26–42 of 42');
    expect(component.hasMore()).toBeFalse();
  });

  it('says "No results" and disables paging when empty', () => {
    component.total = 0;
    expect(component.rangeLabel()).toBe('No results');
    expect(component.totalPages()).toBe(0);
    expect(component.hasMore()).toBeFalse();
  });

  it('emits sizeChange when a new rows-per-page value is picked', () => {
    const seen: number[] = [];
    component.sizeChange.subscribe((s) => seen.push(s));
    component.size = 25;

    component.onSizeChange({ target: { value: '50' } } as unknown as Event);
    expect(seen).toEqual([50]);

    // Re-selecting the same size is a no-op (no redundant reload).
    component.onSizeChange({ target: { value: '50' } } as unknown as Event);
    expect(seen).toEqual([50]);
  });

  it('emits pageChange only for a different, non-negative page', () => {
    const seen: number[] = [];
    component.pageChange.subscribe((p) => seen.push(p));
    component.page = 1;

    component.emit(2);
    component.emit(1);
    component.emit(-1);

    expect(seen).toEqual([2]);
  });

  it('renders the rows selector and range label above the results', () => {
    component.total = 100;
    component.size = 25;
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    const options = Array.from(el.querySelectorAll('option')).map((o) => o.textContent?.trim());
    expect(options).toEqual(['10', '25', '50', '100']);
    expect(el.querySelector('.pager-range')?.textContent).toContain('Showing 1–25 of 100');
    expect(el.textContent).toContain('Page 1 of 4');
    expect(el.querySelector('.pager-top')).withContext('full variant is the header').not.toBeNull();
  });

  it('renders the nav variant as buttons only, for the footer', () => {
    component.total = 100;
    component.size = 25;
    component.variant = 'nav';
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('select')).toBeNull();
    expect(el.querySelector('.pager-range')).toBeNull();
    expect(el.querySelector('.pager-top')).toBeNull();
    expect(el.textContent).toContain('Page 1 of 4');
  });
});
