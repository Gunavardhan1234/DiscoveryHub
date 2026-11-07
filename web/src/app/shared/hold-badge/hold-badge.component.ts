import { Component, Input } from '@angular/core';

/**
 * HoldBadge — renders hold state on a message row or case header (FR-4.4).
 * {@code onHold=true} shows "ON HOLD" in red with the count of overlapping
 * holds; {@code false} shows nothing. The hold <em>set</em> is the source of
 * truth, so the count is the size of that set, never a lone boolean.
 */
@Component({
  selector: 'app-hold-badge',
  standalone: true,
  template: `@if (onHold) {
    <span class="badge badge-hold" title="Protected by {{ count }} legal hold(s)">
      ON HOLD@if (count > 1) { · {{ count }} }
    </span>
  }`,
})
export class HoldBadgeComponent {
  @Input() onHold = false;
  @Input() count = 0;
}
