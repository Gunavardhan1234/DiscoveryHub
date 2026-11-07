import { Component, Input } from '@angular/core';

/**
 * Renders a status as a colored pill. Status names map to global CSS classes
 * `.status-<NAME>` in styles.css, so every screen shows the same colour for
 * the same status (DRAFT, ACTIVE, PENDING, RUNNING, COMPLETED, FAILED, …).
 */
@Component({
  selector: 'app-status-chip',
  standalone: true,
  template: `<span class="badge status-{{ value }}">{{ value }}</span>`,
})
export class StatusChipComponent {
  @Input({ required: true }) value = '';
}
