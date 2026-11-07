import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SearchService } from './search.service';
import { QuerySpec } from '../search.model';

describe('SearchService', () => {
  let service: SearchService;
  let http: HttpTestingController;

  const spec: QuerySpec = { q: 'wire transfer', filters: { from: '2026-01-01', to: '2026-06-30', types: ['EMAIL'] }, sort: 'RELEVANCE', page: 0, size: 25 };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(SearchService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts a full-text + filter query to /api/search', () => {
    let result: { total: number } | undefined;
    service.search(spec).subscribe((r) => (result = r));
    const req = http.expectOne((r) => r.url === '/api/search');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBe(spec);
    req.flush({ total: 1, page: 0, size: 25, hits: [] });
    expect(result?.total).toBe(1);
  });

  it('posts a cheap scope count to /api/search/scope-count', () => {
    let count: number | undefined;
    service.count(spec).subscribe((c) => (count = c.count));
    const req = http.expectOne((r) => r.url === '/api/search/scope-count');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBe(spec);
    req.flush({ count: 42 });
    expect(count).toBe(42);
  });

  it('streams all matching ids from /api/search/scope-ids', () => {
    let ids: string[] | undefined;
    service.scopeIds(spec).subscribe((r) => (ids = r.ids));
    const req = http.expectOne((r) => r.url === '/api/search/scope-ids');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBe(spec);
    req.flush({ ids: ['m-1', 'm-2', 'm-3'] });
    expect(ids).toEqual(['m-1', 'm-2', 'm-3']);
  });

  it('fetches facets from /api/search/facets with the q param', () => {
    let total: number | undefined;
    service.facets('wire transfer').subscribe((f) => (total = f.total));
    const req = http.expectOne((r) => r.url === '/api/search/facets');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('wire transfer');
    req.flush({ total: 10, byType: { EMAIL: 7, CHAT: 3 }, byCustodian: { 'c-0007': 4 }, onHoldCount: 1, hasAttachmentCount: 2 });
    expect(total).toBe(10);
  });

  it('omits the q param when the query is empty', () => {
    service.facets('').subscribe();
    const req = http.expectOne((r) => r.url === '/api/search/facets');
    expect(req.request.params.has('q')).toBeFalse();
    req.flush({ total: 0, byType: {}, byCustodian: {}, onHoldCount: 0, hasAttachmentCount: 0 });
  });
});
