import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuditService } from './audit.service';

describe('AuditService', () => {
  let service: AuditService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuditService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('passes every filter as a query param (FR-7.4)', () => {
    service
      .list({ caseId: 'case-42', actor: 'i-harriet', action: 'HOLD_PLACED', targetType: 'HOLD', from: '2026-09-08T00:00:00Z', to: '2026-09-09T00:00:00Z' })
      .subscribe();

    const req = http.expectOne((r) => r.url === '/api/audit');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('caseId')).toBe('case-42');
    expect(req.request.params.get('actor')).toBe('i-harriet');
    expect(req.request.params.get('action')).toBe('HOLD_PLACED');
    expect(req.request.params.get('targetType')).toBe('HOLD');
    expect(req.request.params.get('from')).toBe('2026-09-08T00:00:00Z');
    expect(req.request.params.get('to')).toBe('2026-09-09T00:00:00Z');
    req.flush([]);
  });

  it('omits empty filters', () => {
    service.list({}).subscribe();
    const req = http.expectOne((r) => r.url === '/api/audit');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('sends page/size and reads the total from X-Total-Count', () => {
    let page: { items: unknown[]; total: number } | undefined;
    service.list({ page: 2, size: 25 }).subscribe((p) => (page = p));

    const req = http.expectOne((r) => r.url === '/api/audit');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('25');
    req.flush([{ eventId: 'evt-1' }], { headers: { 'X-Total-Count': '100' } });

    expect(page!.total).toBe(100);
    expect(page!.items.length).toBe(1);
  });

  it('falls back to the row count when X-Total-Count is missing', () => {
    let page: { items: unknown[]; total: number } | undefined;
    service.list({}).subscribe((p) => (page = p));
    http.expectOne((r) => r.url === '/api/audit').flush([{ eventId: 'a' }, { eventId: 'b' }]);
    expect(page!.total).toBe(2);
  });

  it('verifies the per-case hash chain', () => {
    let valid: boolean | undefined;
    service.verifyChain('case-42').subscribe((v) => (valid = v.valid));
    const req = http.expectOne((r) => r.url === '/api/audit/verify-chain');
    expect(req.request.params.get('caseId')).toBe('case-42');
    req.flush({ caseId: 'case-42', valid: true, entries: 3 });
    expect(valid).toBeTrue();
  });
});
