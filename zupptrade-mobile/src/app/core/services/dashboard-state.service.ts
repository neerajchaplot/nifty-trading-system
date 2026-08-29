import { Injectable, OnDestroy } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, EMPTY, Subscription, interval } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { Agent1Signal } from '../models/agent1-signal.model';
import { ActiveTrade } from '../models/trade.model';
import { Agent1Service } from './agent1.service';
import { Agent3Service } from './agent3.service';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class DashboardStateService implements OnDestroy {
  private readonly signal$ = new BehaviorSubject<Agent1Signal | null>(null);
  private readonly activeTrades$ = new BehaviorSubject<ActiveTrade[]>([]);
  private readonly signalLoading$ = new BehaviorSubject<boolean>(false);
  private readonly signalError$ = new BehaviorSubject<string | null>(null);
  private readonly connectionError$ = new BehaviorSubject<boolean>(false);

  readonly signal = this.signal$.asObservable();
  readonly activeTrades = this.activeTrades$.asObservable();
  readonly signalLoading = this.signalLoading$.asObservable();
  readonly signalError = this.signalError$.asObservable();
  /** True when a poll couldn't reach the server (network down / 5xx) — data shown is stale. */
  readonly connectionError = this.connectionError$.asObservable();

  private subs = new Subscription();

  // Authoritative expiry from the backend (/next-expiry, holiday-aware); client Tuesday is only a fallback.
  private expiryDate = this.nextTuesdayIso();

  constructor(
    private agent1: Agent1Service,
    private agent3: Agent3Service,
  ) {
    this.startPolling();
  }

  private startPolling(): void {
    // Signal poll — catch INSIDE switchMap so one failure doesn't kill the stream.
    this.subs.add(
      interval(environment.marketPollIntervalMs).pipe(
        switchMap(() => this.agent1.latest(this.expiryDate).pipe(
          catchError((e: HttpErrorResponse) => { this.flagConnection(e); return EMPTY; }),
        )),
      ).subscribe(s => { this.signal$.next(s); this.signalError$.next(null); this.connectionError$.next(false); }),
    );

    // Active trades poll
    this.subs.add(
      interval(environment.tradesPollIntervalMs).pipe(
        switchMap(() => this.agent3.activeTrades().pipe(
          catchError((e: HttpErrorResponse) => { this.flagConnection(e); return EMPTY; }),
        )),
      ).subscribe(t => { this.activeTrades$.next(t); this.connectionError$.next(false); }),
    );

    // Resolve the authoritative expiry from the backend BEFORE the first load, then fetch.
    this.subs.add(this.agent1.nextExpiry().subscribe({
      next: r => { if (r?.nextExpiry) this.expiryDate = r.nextExpiry; this.refreshSignal(); },
      error: () => this.refreshSignal(),
    }));
    this.agent3.activeTrades().subscribe({ next: t => this.activeTrades$.next(t), error: () => {} });
  }

  /** Flag ONLY genuine connectivity/server problems (network or 5xx) — not a 4xx business/auth response. */
  private flagConnection(err: HttpErrorResponse): void {
    if (!err || err.status === 0 || err.status >= 500) this.connectionError$.next(true);
  }

  refreshSignal(): void {
    this.signalLoading$.next(true);
    this.signalError$.next(null);
    this.agent1.latest(this.expiryDate).subscribe({
      next: s => { this.signal$.next(s); this.signalLoading$.next(false); this.connectionError$.next(false); },
      error: (err: HttpErrorResponse) => {
        this.signalLoading$.next(false);
        this.flagConnection(err);
        this.signalError$.next(err?.error?.detail ?? 'Signal unavailable');
      },
    });
  }

  /**
   * GENERATE a fresh signal (POST /score) — mirrors the web's "Generate Signal".
   * The regular polls only READ /latest; a brand-new user has no signal until this runs.
   */
  generateSignal(commentary?: string): void {
    this.signalLoading$.next(true);
    this.signalError$.next(null);
    this.agent1.score({ commentary: commentary?.trim() || undefined, marketaux_fetch: true }).subscribe({
      next: s => { this.signal$.next(s); this.signalLoading$.next(false); this.connectionError$.next(false); },
      error: (err: HttpErrorResponse) => {
        this.signalLoading$.next(false);
        this.flagConnection(err);
        this.signalError$.next(err?.error?.detail ?? 'Failed to generate signal.');
      },
    });
  }

  refreshTrades(): void {
    this.agent3.activeTrades().subscribe({ next: t => this.activeTrades$.next(t), error: () => {} });
  }

  nextTuesdayIso(): string {
    const now = new Date();
    const day = now.getDay();
    const daysUntilTuesday = (2 - day + 7) % 7 || 7;
    const tuesday = new Date(now);
    tuesday.setDate(now.getDate() + daysUntilTuesday);
    return tuesday.toISOString().split('T')[0];
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }
}
