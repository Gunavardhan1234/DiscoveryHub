import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { ErrorService } from '../services/error.service';

/** Set on a request's HttpContext to suppress the shared error toast for an
 *  expected failure (e.g. the disposition demo probing whether a message was
 *  deleted — a 404 there is the expected/desired outcome, not an error). */
export const SKIP_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/** Surfaces HTTP failures to the shared error toast, then re-throws to callers. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const errors = inject(ErrorService);
  return next(req).pipe(
    catchError((err) => {
      if (!req.context.get(SKIP_ERROR_TOAST)) {
        errors.show(err);
      }
      return throwError(() => err);
    }),
  );
};
