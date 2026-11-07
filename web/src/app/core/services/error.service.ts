import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '../search.model';

export interface Toast {
  id: number;
  message: string;
  code?: string;
}

/**
 * Global error-toast bus. The HTTP error interceptor feeds it; the shared toast
 * component renders it. Part of the shared shell owned by Member 2.
 */
@Injectable({ providedIn: 'root' })
export class ErrorService {
  private seq = 0;
  private readonly toasts = new BehaviorSubject<Toast[]>([]);
  readonly toasts$: Observable<Toast[]> = this.toasts.asObservable();

  show(err: unknown): void {
    const toast: Toast = { id: ++this.seq, message: this.message(err), code: this.code(err) };
    this.toasts.next([...this.toasts.value, toast]);
    setTimeout(() => this.dismiss(toast.id), 6000);
  }

  dismiss(id: number): void {
    this.toasts.next(this.toasts.value.filter((t) => t.id !== id));
  }

  private message(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      const body = err.error as ApiError | string | null;
      if (body && typeof body === 'object') {
        // Backend sends RFC-7807 {detail, code}; prefer the human-readable detail.
        return body.detail || body.message || `Request failed: ${err.status} ${err.statusText}`;
      }
      return `Request failed: ${err.status} ${err.statusText}`;
    }
    return err instanceof Error ? err.message : 'Unexpected error';
  }

  private code(err: unknown): string | undefined {
    if (err instanceof HttpErrorResponse) {
      const body = err.error as ApiError | null;
      return body && typeof body === 'object' ? body.code : undefined;
    }
    return undefined;
  }
}
