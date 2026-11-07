import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * Paginator — shared page control for list/table screens. Emits `pageChange`
 * with the new (zero-based) page number and `sizeChange` with the new page
 * size. Renders "Showing 1–25 of 100 · Page 1 of 4" plus a rows-per-page
 * selector, so a screen only has to hold `page`/`size` and reload.
 *
 * <p>Two variants, so a screen can bracket its results without repeating the
 * same labels twice: {@code full} (the default) carries the rows selector and
 * the range label and belongs *above* the results, where the user can pick a
 * page size without scrolling past 100 rows first; {@code nav} is the
 * buttons-only footer.
 *
 * <p>Changing the size always resets to page 0 — staying on page 4 while the
 * window grows would land the user past the end of the result set.
 */
@Component({
  selector: 'app-paginator',
  standalone: true,
  template: `<div class="pager" [class.pager-top]="variant === 'full'">
    @if (variant === 'full') {
      <label class="pager-size">
        Rows
        <select [value]="size" (change)="onSizeChange($event)" aria-label="Rows per page">
          @for (o of sizeOptions; track o) {
            <option [value]="o">{{ o }}</option>
          }
        </select>
      </label>
      <span class="muted pager-range">{{ rangeLabel() }}</span>
    }
    <span class="pager-spacer"></span>
    <button type="button" class="sm secondary" [disabled]="page <= 0" (click)="emit(0)" title="First page">« First</button>
    <button type="button" class="sm secondary" [disabled]="page <= 0" (click)="emit(page - 1)">‹ Prev</button>
    <span class="muted">Page {{ page + 1 }}@if (totalPages() > 0) {  of {{ totalPages() }} }</span>
    <button type="button" class="sm secondary" [disabled]="!hasMore()" (click)="emit(page + 1)">Next ›</button>
    <button type="button" class="sm secondary" [disabled]="!hasMore()" (click)="emit(totalPages() - 1)" title="Last page">Last »</button>
  </div>`,
})
export class PaginatorComponent {
  @Input() page = 0;
  @Input() size = 25;
  @Input() total = 0;
  /** Rows-per-page choices; 100 is the search-service page cap. */
  @Input() sizeOptions: number[] = [10, 25, 50, 100];
  /** `full` above the results (rows selector + range + nav), `nav` below. */
  @Input() variant: 'full' | 'nav' = 'full';
  @Output() pageChange = new EventEmitter<number>();
  @Output() sizeChange = new EventEmitter<number>();

  totalPages(): number {
    return this.size > 0 ? Math.ceil(this.total / this.size) : 0;
  }

  hasMore(): boolean {
    return this.page + 1 < this.totalPages();
  }

  /** "Showing 26–50 of 100" — the count on this page out of the whole result set. */
  rangeLabel(): string {
    if (this.total <= 0) return 'No results';
    const first = this.page * this.size + 1;
    const last = Math.min(this.total, (this.page + 1) * this.size);
    return `Showing ${first}–${last} of ${this.total}`;
  }

  emit(p: number): void {
    if (p < 0 || p === this.page) return;
    this.pageChange.emit(p);
  }

  onSizeChange(event: Event): void {
    const next = Number((event.target as HTMLSelectElement).value);
    if (!Number.isFinite(next) || next <= 0 || next === this.size) return;
    this.size = next;
    this.sizeChange.emit(next);
  }
}
