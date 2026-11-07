import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ExportService } from './export.service';
import { ExportJob } from '../api.model';

describe('ExportService', () => {
  let service: ExportService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ExportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists jobs, optionally scoped to a case', () => {
    let jobs: ExportJob[] | undefined;
    service.list('case-42').subscribe((j) => (jobs = j as ExportJob[]));

    const req = http.expectOne((r) => r.url === '/api/exports');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('caseId')).toBe('case-42');
    req.flush([{ jobId: 'j-1', status: 'QUEUED', progressPct: 0 }]);

    expect(jobs?.length).toBe(1);
    expect(jobs?.[0].jobId).toBe('j-1');
  });

  it('requests an export with 202 semantics', () => {
    service.request('case-42').subscribe();
    const req = http.expectOne('/api/exports');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ caseId: 'case-42' });
    req.flush({ jobId: 'j-1', status: 'QUEUED', progressPct: 0 });
  });

  it('fetches the pre-signed download url', () => {
    let url: string | undefined;
    service.downloadUrl('j-1').subscribe((d) => (url = d.url));
    const req = http.expectOne('/api/exports/j-1/download-url');
    expect(req.request.method).toBe('GET');
    req.flush({ url: 'http://localstack/exports.zip', expiresAt: '2026-09-09T10:15:00Z' });
    expect(url).toBe('http://localstack/exports.zip');
  });

  it('verifies a package server-side', () => {
    let valid: boolean | undefined;
    service.verify('j-1').subscribe((v) => (valid = v.valid));
    const req = http.expectOne('/api/exports/j-1/verify');
    expect(req.request.method).toBe('POST');
    req.flush({ jobId: 'j-1', valid: true, message: 'ok' });
    expect(valid).toBeTrue();
  });
});
