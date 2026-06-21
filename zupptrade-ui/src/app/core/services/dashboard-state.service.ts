import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, EMPTY, Subscription, interval } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { Agent1Signal } from '../models/agent1-signal.model';
import { ActiveTrade } from '../models/trade.model';
import { Agent1Service } from './agent1.service';
import { Agent3Service } from './agent3.service';
import { environment } from '../../../environments/environment';

/**
 * Singleton service that drives the two polling loops:
 *  - market signal: Agent1 /latest every 10 s
 *  - live trades:   Agent3 /active-trades every 5 s
 *
 * Components subscribe to the BehaviorSubjects via async pipe — no imperative subscriptions needed.
 */
@Injectable({ providedIn: 'root' })
export class DashboardStateService implements OnDestroy {
  private readonly _signal$ = new BehaviorSubject<Agent1Signal | null>(null);
  private readonly _activeTrades$ = new BehaviorSubject<ActiveTrade[]>([]);
  private readonly _expiryDate$ = new BehaviorSubject<string>(nextTuesdayIso());

  readonly signal$ = this._signal$.asObservable();
  readonly activeTrades$ = this._activeTrades$.asObservable();
  readonly expiryDate$ = this._expiryDate$.asObservable();

  private subs = new Subscription();

  constructor(private agent1: Agent1Service, private agent3: Agent3Service) {
    this.startSignalPolling();
    this.startTradesPolling();
  }

  get currentSignal(): Agent1Signal | null {
    return this._signal$.value;
  }

  get currentExpiryDate(): string {
    return this._expiryDate$.value;
  }

  setExpiryDate(date: string): void {
    this._expiryDate$.next(date);
  }

  /** Trigger an immediate signal refresh (e.g. after user clicks Refresh). */
  refreshSignal(): void {
    this.agent1.latest(this._expiryDate$.value).pipe(
      catchError(() => EMPTY)
    ).subscribe(sig => this._signal$.next(sig));
  }

  private startSignalPolling(): void {
    const sub = interval(environment.marketPollIntervalMs).pipe(
      switchMap(() =>
        this.agent1.latest(this._expiryDate$.value).pipe(catchError(() => EMPTY))
      )
    ).subscribe(sig => this._signal$.next(sig));

    // Fetch immediately on startup
    this.refreshSignal();
    this.subs.add(sub);
  }

  private startTradesPolling(): void {
    const sub = interval(environment.tradesPollIntervalMs).pipe(
      switchMap(() =>
        this.agent3.activeTrades().pipe(catchError(() => EMPTY))
      )
    ).subscribe(trades => this._activeTrades$.next(trades));

    this.agent3.activeTrades().pipe(catchError(() => EMPTY))
      .subscribe(trades => this._activeTrades$.next(trades));

    this.subs.add(sub);
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }
}

function nextTuesdayIso(): string {
  const now = new Date();
  const day = now.getDay(); // 0 = Sun, 2 = Tue
  const daysUntilTuesday = (2 - day + 7) % 7 || 7;
  const tuesday = new Date(now);
  tuesday.setDate(now.getDate() + daysUntilTuesday);
  return tuesday.toISOString().slice(0, 10);
}
