import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { DispositionService } from '../../core/services/disposition.service';
import { HoldService } from '../../core/services/hold.service';
import { CaseService } from '../../core/services/case.service';
import { IngestService } from '../../core/services/ingest.service';
import { MessageService } from '../../core/services/message.service';
import { DispositionRun, IngestMessage } from '../../core/api.model';
import { StatusChipComponent } from '../../shared/status-chip/status-chip.component';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { PaginatorComponent } from '../../shared/paginator/paginator.component';

/** One row of the guided demo's live progress log (rendered via the shared
 *  status-chip component, so it reads the same PENDING/RUNNING/COMPLETED/
 *  FAILED colours as the rest of the app). */
interface DemoStep {
  label: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  detail?: string;
}

/**
 * Disposition screen (FR-5, M4). The Day-3 demo screen: trigger a disposition
 * pass and watch it delete expired unheld messages, skip held ones, and record
 * the counts. The {@code @Scheduled} job calls the same backend; this is the
 * manual trigger so you don't have to wait for the next tick.
 *
 * <p>{@code POST /run} returns immediately with a {@code RUNNING} row — the
 * scan-and-delete pass can take a while over a large corpus, so this screen
 * polls {@link DispositionService#list} every couple seconds until the latest
 * run leaves {@code RUNNING}, the same pattern the Exports screen uses.
 *
 * <p>The "guided demo" card below is a presentation convenience: it drives
 * the same public APIs {@code scripts/demo-disposition.sh} does (ingest,
 * hold, disposition) end to end from one button, with a live step log, so
 * the FR-5/FR-4.6 "held survives, expired-unheld gets deleted" story can be
 * shown without a terminal during a live demo.
 *
 * <p><strong>Never shortens the retention policy.</strong> An earlier version
 * set CHAT retention to a few seconds so the demo wouldn't need to wait — but
 * against a populated corpus (the generator backdates {@code sentAt} up to
 * ~7 days) that makes the entire existing CHAT corpus eligible too, and the
 * very next disposition run (this one, or the {@code @Scheduled} tick every
 * 2 min) deletes all of it. Instead, this reads whatever CHAT retention is
 * currently configured and backdates only the two demo messages' {@code
 * sentAt} far enough past it to be individually eligible — every other
 * message keeps its real timestamp and is never touched.
 */
@Component({
  selector: 'app-disposition',
  standalone: true,
  imports: [StatusChipComponent, EmptyStateComponent, PaginatorComponent],
  template: `
    <div class="page-head">
      <h2>Disposition</h2>
      <button type="button" class="sm" [disabled]="running()" (click)="run()">
        @if (running()) { Running… } @else { Run disposition now }
      </button>
    </div>
    <p class="subtitle muted" style="margin-top:-0.5rem;margin-bottom:1rem">
      Deletes messages past their retention window with an empty hold set. Messages still under a legal hold are <strong>skipped</strong>, never deleted — the hold-vs-retention guarantee.
    </p>

    <div class="card" style="margin-bottom:1rem">
      <div class="card-title">Guided demo</div>
      <p class="muted" style="margin:0 0 0.75rem">
        One click, end to end: ingests a held and an unheld CHAT message backdated past the current CHAT retention policy, places a hold on the first, runs disposition, then verifies the held message survives (200) and the unheld one is gone (404). The retention policy itself is never modified, so the rest of the corpus is never at risk.
      </p>
      <button type="button" class="sm secondary" [disabled]="demoRunning()" (click)="runGuidedDemo()">
        @if (demoRunning()) { Running demo… } @else { Run guided demo }
      </button>
      @if (demoSteps().length > 0) {
        <div class="timeline" style="margin-top:1rem">
          @for (step of demoSteps(); track step.label) {
            <div class="event">
              <app-status-chip [value]="step.status" />
              <span style="margin-left:0.5rem">{{ step.label }}</span>
              @if (step.detail) { <span class="faint"> — {{ step.detail }}</span> }
            </div>
          }
        </div>
      }
    </div>

    @if (latest()) {
      <div class="card" style="margin-bottom:1rem">
        <div class="card-title">Latest run</div>
        <div class="grid grid-4">
          <div class="stat">
            <span class="stat-value">{{ latest()!.deleted }}</span>
            <span class="stat-label">Deleted</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ latest()!.skippedOnHold }}</span>
            <span class="stat-label">Skipped (held)</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ latest()!.errors }}</span>
            <span class="stat-label">Errors</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ latest()!.status || '—' }}</span>
            <span class="stat-label">Status</span>
          </div>
        </div>
        @if (byTypeEntries().length > 0) {
          <div style="margin-top:0.75rem">
            <div class="card-title">Deleted by type</div>
            <div class="row" style="flex-wrap:wrap">
              @for (entry of byTypeEntries();track entry[0]) {
                <span class="badge badge-muted">{{ entry[0] }}: {{ entry[1] }}</span>
              }
            </div>
          </div>
        }
      </div>
    }

    <h3>History</h3>
    @if (runs().length > 0) {
      <app-paginator
        [page]="page()"
        [size]="size()"
        [total]="runs().length"
        (pageChange)="goToPage($event)"
        (sizeChange)="changeSize($event)" />
    }
    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>Run</th><th>Status</th><th>Started</th><th>Finished</th><th>Deleted</th><th>Skipped</th><th>Errors</th></tr>
        </thead>
        <tbody>
          @for (r of pagedRuns();track r.id) {
            <tr>
              <td class="faint">{{ r.id }}</td>
              <td>@if (r.status) { <app-status-chip [value]="r.status" /> }</td>
              <td class="faint">{{ r.startedAt || '—' }}</td>
              <td class="faint">{{ r.finishedAt || '—' }}</td>
              <td>{{ r.deleted }}</td>
              <td>{{ r.skippedOnHold }}</td>
              <td>{{ r.errors }}</td>
            </tr>
          } @empty {
            <tr><td colspan="7"><app-empty-state icon="♻️" title="No disposition runs yet" hint="Click 'Run disposition now' to execute a pass." /></td></tr>
          }
        </tbody>
      </table>
    </div>
    @if (runs().length > 0) {
      <app-paginator
        variant="nav"
        [page]="page()"
        [size]="size()"
        [total]="runs().length"
        (pageChange)="goToPage($event)" />
    }
  `,
})
export class DispositionComponent implements OnInit, OnDestroy {
  private readonly disposition = inject(DispositionService);
  private readonly holdService = inject(HoldService);
  private readonly caseService = inject(CaseService);
  private readonly ingest = inject(IngestService);
  private readonly messageService = inject(MessageService);

  readonly runs = signal<DispositionRun[]>([]);
  readonly latest = signal<DispositionRun | null>(null);
  readonly running = signal(false);
  private pollTimer?: ReturnType<typeof setInterval>;

  readonly demoRunning = signal(false);
  readonly demoSteps = signal<DemoStep[]>([]);

  /** History paging. Unlike audit/cases this is client-side: {@code GET /runs}
   *  has no {@code page}/{@code size} params and returns the whole run list, so
   *  the table is a slice of {@link #runs} and paging never refetches. The
   *  2-second completion poll therefore keeps the operator's current page. */
  readonly page = signal(0);
  readonly size = signal(25);
  readonly pagedRuns = computed(() => {
    const start = this.page() * this.size();
    return this.runs().slice(start, start + this.size());
  });

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  load(): void {
    this.disposition.list().subscribe({
      next: (r) => {
        this.runs.set(r ?? []);
        this.clampPage();
        this.latest.set((r ?? [])[0] ?? null);
        this.running.set((r ?? [])[0]?.status === 'RUNNING');
        if (!this.running()) {
          this.stopPolling();
        }
      },
      error: () => { this.runs.set([]); this.page.set(0); this.latest.set(null); this.running.set(false); this.stopPolling(); },
    });
  }

  goToPage(p: number): void {
    this.page.set(Math.max(0, p));
    this.clampPage();
  }

  /** A bigger/smaller window invalidates the offset, so restart at page 0 —
   *  same rule as the audit and cases screens. */
  changeSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
  }

  /** Client-side paging has no server to re-clamp the offset, so keep the page
   *  in range ourselves when a poll returns a shorter run list. */
  private clampPage(): void {
    const last = Math.max(0, Math.ceil(this.runs().length / this.size()) - 1);
    if (this.page() > last) {
      this.page.set(last);
    }
  }

  /** Kicks off a pass; the request returns immediately (202 + RUNNING row), so we poll for completion. */
  run(): void {
    this.disposition.run().subscribe({
      next: (r) => {
        this.latest.set(r);
        this.running.set(r.status === 'RUNNING');
        this.load();
        if (!this.pollTimer) {
          this.pollTimer = setInterval(() => this.load(), 2000);
        }
      },
      error: () => this.running.set(false),
    });
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
  }

  byTypeEntries(): [string, number][] {
    const map = this.latest()?.deletedByType;
    return map ? Object.entries(map) : [];
  }

  /**
   * The one-click FR-5/FR-4.6 story: set a short CHAT retention, ingest a
   * held and an unheld message, place the hold, wait it out, run disposition,
   * then verify the held message survives (200) and the unheld one is gone
   * (404). Mirrors scripts/demo-disposition.sh so the same rehearsed flow is
   * available without a terminal during a live presentation.
   */
  async runGuidedDemo(): Promise<void> {
    if (this.demoRunning()) return;
    this.demoRunning.set(true);

    const steps: DemoStep[] = [
      { label: 'Read current CHAT retention policy' },
      { label: 'Ingest protected (held) message, backdated past retention' },
      { label: 'Ingest doomed (unheld) message, backdated past retention' },
      { label: 'Create demo case' },
      { label: 'Place hold on the protected custodian' },
      { label: 'Wait for the hold to apply' },
      { label: 'Run disposition' },
      { label: 'Verify held message survived' },
      { label: 'Verify unheld message was deleted' },
    ].map((s) => ({ ...s, status: 'PENDING' as const }));
    this.demoSteps.set(steps);

    const setStep = (i: number, status: DemoStep['status'], detail?: string) =>
      this.demoSteps.update((s) => s.map((step, idx) => (idx === i ? { ...step, status, detail } : step)));

    const token = `demo${Date.now()}`;
    try {
      setStep(0, 'RUNNING');
      const sentAt = await this.backdatedSentAt();
      setStep(0, 'COMPLETED', sentAt);

      setStep(1, 'RUNNING');
      const protectedCustodian = `protected-${token}`;
      const protectedMsg = await firstValueFrom(this.ingest.ingest(this.buildDemoMessage(protectedCustodian, 'protected', token, sentAt)));
      setStep(1, 'COMPLETED', protectedMsg.messageId);

      setStep(2, 'RUNNING');
      const doomedCustodian = `doomed-${token}`;
      const doomedMsg = await firstValueFrom(this.ingest.ingest(this.buildDemoMessage(doomedCustodian, 'doomed', token, sentAt)));
      setStep(2, 'COMPLETED', doomedMsg.messageId);

      setStep(3, 'RUNNING');
      const demoCase = await firstValueFrom(
        this.caseService.create({
          name: `Disposition demo ${token}`,
          description: 'FR-5 guided demo',
          matterType: 'INVESTIGATION',
          owner: 'demo-ui',
        }),
      );
      setStep(3, 'COMPLETED', demoCase.id);

      setStep(4, 'RUNNING');
      const hold = await firstValueFrom(
        this.holdService.place({ caseId: demoCase.id, custodians: [protectedCustodian] }),
      );
      setStep(4, 'COMPLETED', hold.holdId);

      setStep(5, 'RUNNING');
      await this.waitForHoldActive(hold.holdId, 30000);
      setStep(5, 'COMPLETED');

      setStep(6, 'RUNNING');
      const runId = await this.triggerAndAwaitDisposition(30000);
      setStep(6, 'COMPLETED', runId);

      setStep(7, 'RUNNING');
      const protectedSurvived = await firstValueFrom(this.messageService.exists(protectedMsg.messageId));
      setStep(7, protectedSurvived ? 'COMPLETED' : 'FAILED');

      setStep(8, 'RUNNING');
      const doomedDeleted = !(await firstValueFrom(this.messageService.exists(doomedMsg.messageId)));
      setStep(8, doomedDeleted ? 'COMPLETED' : 'FAILED');

      this.load();
    } catch (e) {
      this.demoSteps.update((s) =>
        s.map((step) => (step.status === 'RUNNING' ? { ...step, status: 'FAILED', detail: this.errorDetail(e) } : step)),
      );
    } finally {
      this.demoRunning.set(false);
    }
  }

  /** Reads the live CHAT retention policy and returns an ISO timestamp
   *  (retention + 1 day in the past) — far enough back that the two demo
   *  messages are individually eligible for disposition without touching
   *  the policy itself, so the rest of the corpus is never at risk. */
  private async backdatedSentAt(): Promise<string> {
    const policies = await firstValueFrom(this.holdService.retentionPolicies());
    const chat = (policies ?? []).find((p) => p.commType === 'CHAT');
    if (!chat) throw new Error('no CHAT retention policy configured');
    const retentionSecs = this.isoDurationToSeconds(chat.duration);
    if (retentionSecs <= 0) throw new Error(`could not parse CHAT retention '${chat.duration}'`);
    const backdateMs = (retentionSecs + 86400) * 1000;
    return new Date(Date.now() - backdateMs).toISOString();
  }

  /** Minimal ISO-8601 duration parser for the subset the backend accepts
   *  (java.time.Duration.parse: days/hours/minutes/seconds, e.g. "P3650D",
   *  "PT2M", "P1DT2H"). */
  private isoDurationToSeconds(iso: string): number {
    const m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso ?? '');
    if (!m) return 0;
    const [, d, h, mi, s] = m;
    return (Number(d ?? 0) * 86400) + (Number(h ?? 0) * 3600) + (Number(mi ?? 0) * 60) + Number(s ?? 0);
  }

  private buildDemoMessage(custodian: string, label: string, token: string, sentAt: string): IngestMessage {
    return {
      externalMessageId: `${label}-${token}`,
      type: 'CHAT',
      subject: `Disposition demo (${label})`,
      body: `Disposition demo payload ${token} — ${label} message.`,
      threadId: `thread-${label}-${token}`,
      custodian,
      participants: [
        { name: 'Demo Sender', address: 'sender@demo.local', role: 'FROM' },
        { name: 'Demo Recipient', address: 'recipient@demo.local', role: 'TO' },
      ],
      sentAt,
    };
  }

  private async waitForHoldActive(holdId: string, timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const hold = await firstValueFrom(this.holdService.get(holdId));
      if (hold.status === 'ACTIVE' && hold.appliedCount >= 1) return;
      if (Date.now() >= deadline) throw new Error(`hold ${holdId} did not reach ACTIVE in time`);
      await this.delay(1000);
    }
  }

  /** Triggers a run; a 409 (a scheduled tick already holds the lock) is
   *  treated as a normal branch — we just track whichever run is in flight. */
  private async triggerAndAwaitDisposition(timeoutMs: number): Promise<string> {
    let runId: string | undefined;
    try {
      const started = await firstValueFrom(this.disposition.runSilently());
      runId = started.id;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        const latest = await firstValueFrom(this.disposition.latest());
        runId = latest.id;
      } else {
        throw e;
      }
    }
    if (!runId) throw new Error('disposition run did not return an id');

    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const latest = await firstValueFrom(this.disposition.latest());
      if (latest.status && latest.status !== 'RUNNING') return runId;
      if (Date.now() >= deadline) throw new Error(`disposition run ${runId} still RUNNING`);
      await this.delay(1000);
    }
  }

  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private errorDetail(e: unknown): string {
    if (e instanceof HttpErrorResponse) return `http ${e.status}`;
    return e instanceof Error ? e.message : 'failed';
  }
}
