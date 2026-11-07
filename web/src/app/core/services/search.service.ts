import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { FacetResult, QuerySpec, SearchResult } from '../search.model';

/** Angular client for the search-service REST API. */
@Injectable({ providedIn: 'root' })
export class SearchService {
  constructor(private readonly http: HttpClient) {}

  search(spec: QuerySpec): Observable<SearchResult> {
    return this.http.post<SearchResult>('/api/search', spec);
  }

  count(spec: QuerySpec): Observable<{ count: number }> {
    return this.http.post<{ count: number }>('/api/search/scope-count', spec);
  }

  /** Stream every matching message id (PIT + search_after) so the entire result
   *  set — not just the current page — can be added to a case (FR-3.6 / FR-4). */
  scopeIds(spec: QuerySpec): Observable<{ ids: string[] }> {
    return this.http.post<{ ids: string[] }>('/api/search/scope-ids', spec);
  }

  /** Aggregated counts for a scope overview (architecture §2.2). The endpoint
   *  reflects the text query only; structured filters are not applied server-side. */
  facets(q: string): Observable<FacetResult> {
    let params = new HttpParams();
    if (q) {
      params = params.set('q', q);
    }
    return this.http.get<FacetResult>('/api/search/facets', { params });
  }
}
