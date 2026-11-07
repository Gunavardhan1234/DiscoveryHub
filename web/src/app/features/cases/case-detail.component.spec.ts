import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { CaseDetailComponent } from './case-detail.component';
import { CaseDetail } from '../../core/api.model';

/**
 * FR-4.4 on the Case Detail screen: hold status must be visible on *every*
 * message, not just as the case-level held total. The Evidence tab resolves
 * the hold set for its message ids from archive-service's bulk read and shows
 * the shared hold badge; the column must degrade quietly when archive is down.
 */
describe('CaseDetailComponent evidence hold status', () => {
  let fixture: ComponentFixture<CaseDetailComponent>;
  let component: CaseDetailComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CaseDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'case-1' } } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CaseDetailComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function detail(messageIds: string[]): CaseDetail {
    return {
      id: 'case-1',
      name: 'Project Kingfisher',
      matterType: 'INVESTIGATION',
      owner: 'investigator@demo',
      status: 'ACTIVE',
      createdAt: '2026-09-01T00:00:00Z',
      version: 1,
      custodians: [],
      evidence: messageIds.map((m, i) => ({ id: `e-${i}`, caseId: 'case-1', messageId: m })),
      savedSearches: [],
      counts: { custodianCount: 0, evidenceCount: messageIds.length, savedSearchCount: 0, heldCount: 0 },
    } as CaseDetail;
  }

  /** Flushes GET /api/cases/case-1/detail; returns the follow-up bulk request. */
  function flushDetail(messageIds: string[]) {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/cases/case-1/detail').flush(detail(messageIds));
    fixture.detectChanges();
    return messageIds.length > 0 ? http.expectOne((r) => r.url === '/api/messages') : null;
  }

  it('requests the hold set for exactly the evidence message ids', () => {
    const req = flushDetail(['m-1', 'm-2'])!;

    expect(req.request.params.get('ids')).toBe('m-1,m-2');
    req.flush({ messages: [] });
  });

  it('renders ON HOLD for held evidence and nothing for unheld', () => {
    const req = flushDetail(['m-held', 'm-free'])!;
    req.flush({
      messages: [
        { id: 'm-held', holds: ['h-1'], onHold: true },
        { id: 'm-free', holds: [], onHold: false },
      ],
    });
    fixture.detectChanges();

    expect(component.holdCount('m-held')).toBe(1);
    expect(component.holdCount('m-free')).toBe(0);
    const badges = fixture.nativeElement.querySelectorAll('.badge-hold');
    expect(badges.length).toBe(1);
    expect(badges[0].textContent).toContain('ON HOLD');
  });

  /** Overlapping holds (FR-4.5) — the badge shows the size of the hold set. */
  it('shows the overlapping hold count when a message has several holds', () => {
    const req = flushDetail(['m-1'])!;
    req.flush({ messages: [{ id: 'm-1', holds: ['h-1', 'h-2', 'h-3'], onHold: true }] });
    fixture.detectChanges();

    expect(component.holdCount('m-1')).toBe(3);
    expect(fixture.nativeElement.querySelector('.badge-hold').textContent).toContain('3');
  });

  it('prefers the hold set size over the denormalized onHold flag', () => {
    const req = flushDetail(['m-1'])!;
    // Stale mirror: flag says held, but the authoritative set is empty.
    req.flush({ messages: [{ id: 'm-1', holds: [], onHold: true }] });
    fixture.detectChanges();

    expect(component.holdCount('m-1')).toBe(0);
  });

  it('falls back to the flag when the set is absent from the document', () => {
    const req = flushDetail(['m-1'])!;
    req.flush({ messages: [{ id: 'm-1', onHold: true }] });
    fixture.detectChanges();

    expect(component.holdCount('m-1')).toBe(1);
  });

  it('treats a message missing from the bulk response as not held', () => {
    const req = flushDetail(['m-1', 'm-gone'])!;
    req.flush({ messages: [{ id: 'm-1', holds: ['h-1'] }] });
    fixture.detectChanges();

    expect(component.holdCount('m-gone')).toBe(0);
  });

  it('degrades the column instead of failing when archive-service is down', () => {
    const req = flushDetail(['m-1'])!;
    req.flush({ detail: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(component.holdCount('m-1')).toBe(0);
    expect(fixture.nativeElement.querySelector('.badge-hold')).toBeNull();
    // The rest of the tab still rendered.
    expect(fixture.nativeElement.textContent).toContain('m-1');
  });

  it('makes no bulk request for a case with no evidence', () => {
    flushDetail([]);

    http.expectNone((r) => r.url === '/api/messages');
    expect(component.holdCounts()).toEqual({});
  });
});
