import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

/**
 * Global in-flight-request counter that drives the shared loading spinner.
 * Shared shell infrastructure owned by Member 2 — every screen consumes this.
 */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private count = 0;
  private readonly subject = new BehaviorSubject<boolean>(false);

  readonly loading$: Observable<boolean> = this.subject.asObservable();

  start(): void {
    this.count++;
    this.subject.next(this.count > 0);
  }

  stop(): void {
    if (this.count > 0) {
      this.count--;
    }
    this.subject.next(this.count > 0);
  }
}
