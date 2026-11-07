import { Component } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { LoadingService } from '../../core/services/loading.service';

/** Shared full-screen loading spinner, driven by the global in-flight counter. */
@Component({
  selector: 'app-loading-spinner',
  standalone: true,
  imports: [AsyncPipe],
  template: `@if (loading$ | async) {
    <div class="spinner-overlay" aria-busy="true" aria-live="polite">
      <div class="spinner" role="status"></div>
    </div>
  }`,
})
export class LoadingSpinnerComponent {
  readonly loading$ = this.loading.loading$;
  constructor(private readonly loading: LoadingService) {}
}
