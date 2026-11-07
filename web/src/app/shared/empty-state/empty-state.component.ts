import { Component, Input } from '@angular/core';

/**
 * EmptyState — consistent placeholder shown when a list/table is empty.
 * Keeps messaging calm and helpful rather than a blank pane.
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  template: `<div class="empty">
    <div class="empty-icon">{{ icon }}</div>
    <h3>{{ title }}</h3>
    @if (hint) { <p class="muted">{{ hint }}</p> }
  </div>`,
})
export class EmptyStateComponent {
  @Input() icon = '📭';
  @Input({ required: true }) title = '';
  @Input() hint = '';
}
