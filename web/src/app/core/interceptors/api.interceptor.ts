import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../../../environments/environment';

/**
 * Prepends the configured API base URL to same-origin `/api/**` requests and stamps
 * a correlation id so a request can be traced across the gateway and services.
 */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  let url = req.url;
  if (url.startsWith('/api/')) {
    url = `${environment.apiUrl}${url}`;
  }
  const correlationId = crypto.randomUUID();
  return next(req.clone({ url, setHeaders: { 'X-Correlation-Id': correlationId } }));
};
