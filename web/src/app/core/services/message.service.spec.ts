import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MESSAGE_BATCH_SIZE, MessageService } from './message.service';

/**
 * Tests for the archive-service bulk read that backs FR-4.4 hold status on
 * Case Detail's evidence rows. The ids travel in a query param, so batching
 * and graceful degradation are the behaviours that matter.
 */
describe('MessageService.byIds', () => {
  let service: MessageService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MessageService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MessageService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('fetches one batch as a single comma-separated request', () => {
    let result: unknown[] = [];
    service.byIds(['m-1', 'm-2']).subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === '/api/messages');
    expect(req.request.params.get('ids')).toBe('m-1,m-2');
    req.flush({ messages: [{ id: 'm-1', holds: ['h-1'] }, { id: 'm-2', holds: [] }] });

    expect(result.length).toBe(2);
  });

  it('makes no request for an empty id list', () => {
    let called = false;
    service.byIds([]).subscribe(() => (called = true));

    http.expectNone(() => true);
    expect(called).toBeTrue();
  });

  it('de-duplicates and drops empty ids', () => {
    service.byIds(['m-1', 'm-1', '', 'm-2']).subscribe();

    const req = http.expectOne((r) => r.url === '/api/messages');
    expect(req.request.params.get('ids')).toBe('m-1,m-2');
    req.flush({ messages: [] });
  });

  it('chunks past the batch size and concatenates the results', () => {
    const ids = Array.from({ length: MESSAGE_BATCH_SIZE + 5 }, (_, i) => `m-${i}`);
    let result: { id: string }[] = [];
    service.byIds(ids).subscribe((r) => (result = r as { id: string }[]));

    const reqs = http.match((r) => r.url === '/api/messages');
    expect(reqs.length).toBe(2);
    expect(reqs[0].request.params.get('ids')!.split(',').length).toBe(MESSAGE_BATCH_SIZE);
    expect(reqs[1].request.params.get('ids')!.split(',').length).toBe(5);

    reqs[0].flush({ messages: [{ id: 'm-0' }] });
    reqs[1].flush({ messages: [{ id: 'm-200' }] });
    expect(result.map((m) => m.id)).toEqual(['m-0', 'm-200']);
  });

  /** Hold status is supplementary — archive being down must not break the page. */
  it('degrades to an empty list when archive-service fails', () => {
    let result: unknown[] | undefined;
    service.byIds(['m-1']).subscribe((r) => (result = r));

    http.expectOne((r) => r.url === '/api/messages')
      .flush({ detail: 'boom' }, { status: 503, statusText: 'Service Unavailable' });

    expect(result).toEqual([]);
  });

  it('tolerates a response with no messages field', () => {
    let result: unknown[] | undefined;
    service.byIds(['m-1']).subscribe((r) => (result = r));

    http.expectOne((r) => r.url === '/api/messages').flush({});

    expect(result).toEqual([]);
  });
});
