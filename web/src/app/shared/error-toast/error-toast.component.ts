import { Component } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { ErrorService } from '../../core/services/error.service';

/** Shared error-toast stack. Click a toast to dismiss it; toasts auto-expire. */
@Component({
  selector: 'app-error-toast',
  standalone: true,
  imports: [AsyncPipe],
  template: `@if (toasts$ | async; as toasts) {
    <div class="toast-stack" aria-live="assertive">
      @for (t of toasts; track t.id) {
        <div class="toast" (click)="errors.dismiss(t.id)">
          @if (t.code) { <strong>{{ t.code }}:</strong> } {{ t.message }}
        </div>
      }
    </div>
  }`,
})
export class ErrorToastComponent {
  readonly toasts$ = this.errors.toasts$;
  constructor(public errors: ErrorService) {}
}
