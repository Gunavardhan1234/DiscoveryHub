import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DownloadUrl, ExportJob, ExportVerify } from '../api.model';

/** Angular client for export-service (job lifecycle + pre-signed download + verify). */
@Injectable({ providedIn: 'root' })
export class ExportService {
  private readonly http = inject(HttpClient);

  list(caseId?: string): Observable<ExportJob[]> {
    let p = new HttpParams();
    if (caseId) p = p.set('caseId', caseId);
    return this.http.get<ExportJob[]>('/api/exports', { params: p });
  }

  get(jobId: string): Observable<ExportJob> {
    return this.http.get<ExportJob>(`/api/exports/${jobId}`);
  }

  request(caseId: string): Observable<ExportJob> {
    return this.http.post<ExportJob>('/api/exports', { caseId });
  }

  downloadUrl(jobId: string): Observable<DownloadUrl> {
    return this.http.get<DownloadUrl>(`/api/exports/${jobId}/download-url`);
  }

  verify(jobId: string): Observable<ExportVerify> {
    return this.http.post<ExportVerify>(`/api/exports/${jobId}/verify`, {});
  }

  retry(jobId: string): Observable<ExportJob> {
    return this.http.post<ExportJob>(`/api/exports/${jobId}/retry`, {});
  }
}
