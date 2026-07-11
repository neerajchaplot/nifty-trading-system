import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Subscription, interval } from 'rxjs';
import { switchMap } from 'rxjs/operators';
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

  readonly signal = this.signal$.asObservable();
  readonly activeTrades = this.activeTrades$.asObservable();
  readonly signalLoading = this.signalLoading$.asObservable();
  readonly signalError = this.signalError$.asObservable();

  private subs = new Subscription();

  constructor(
    private agent1: Agent1Service,
    private agent3: Agent3Service,
  ) {
    this.startPolling();
  }

  private startPolling(): void {
    // Signal poll
    this.subs.add(
      interval(environment.marketPollIntervalMs).pipe(
        switchMap(() => this.agent1.latest(this.nextTuesdayIso())),
      ).subscribe({
        next: s => this.signal$.next(s),
        error: () => {},
      }),
    );

    // Active trades poll
    this.subs.add(
      interval(environment.tradesPollIntervalMs).pipe(
        switchMap(() => this.agent3.activeTrades()),
      ).subscribe({
        next: t => this.activeTrades$.next(t),
        error: () => {},
      }),
    );

    // Initial load
    this.refreshSignal();
    this.agent3.activeTrades().subscribe({ next: t => this.activeTrades$.next(t), error: () => {} });
  }

  refreshSignal(): void {
    this.signalLoading$.next(true);
    this.signalError$.next(null);
    this.agent1.latest(this.nextTuesdayIso()).subscribe({
      next: s => { this.signal$.next(s); this.signalLoading$.next(false); },
      error: err => {
        this.signalLoading$.next(false);
        this.signalError$.next(err?.error?.detail ?? 'Signal unavailable');
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
