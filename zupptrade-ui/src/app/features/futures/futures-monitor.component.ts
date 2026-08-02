import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subject, interval, of } from 'rxjs';
import { catchError, startWith, switchMap, takeUntil } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { FuturesArmCard, FuturesPlanCard } from '../../core/models/futures.model';
import { FuturePlanStatus } from '../../core/models/enums';
import { FuturesService } from '../../core/services/futures.service';

/**
 * Screen 2 — accepted futures plans. Dormant plans (ARMED / BREAK_DETECTED) render greyed
 * with their activation condition; once Agent 5 places the GTT (CONFIRMED / FILLED) the card
 * flips to active styling. Polls the backend on the trades interval.
 */
@Component({
  selector: 'app-futures-monitor',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './futures-monitor.component.html',
  styleUrls: ['./futures-monitor.component.scss'],
})
export class FuturesMonitorComponent implements OnInit, OnDestroy {
  plans: FuturesPlanCard[] = [];
  loaded = false;
  private readonly destroy$ = new Subject<void>();

  constructor(private readonly futures: FuturesService) {}

  ngOnInit(): void {
    interval(environment.tradesPollIntervalMs)
      .pipe(
        startWith(0),
        switchMap(() => this.futures.listActive().pipe(catchError(() => of([] as FuturesPlanCard[])))),
        takeUntil(this.destroy$),
      )
      .subscribe(plans => {
        this.plans = plans;
        this.loaded = true;
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── View helpers ─────────────────────────────────────────────────────────

  chosenArm(plan: FuturesPlanCard): FuturesArmCard | null {
    if (!plan.primaryArm) return plan.arms?.[0] ?? null;
    return plan.arms?.find(a => a.armType === plan.primaryArm) ?? null;
  }

  isActive(status: FuturePlanStatus): boolean {
    return status === 'CONFIRMED' || status === 'FILLED';
  }

  cardClass(plan: FuturesPlanCard): string {
    return this.isActive(plan.status) ? 'fm-card fm-active' : 'fm-card fm-dormant';
  }

  statusLabel(plan: FuturesPlanCard): string {
    switch (plan.status) {
      case 'ARMED': return 'Waiting';
      case 'BREAK_DETECTED': return 'Break detected';
      case 'CONFIRMED': return 'Active';
      case 'FILLED': return 'Active';
      default: return plan.status;
    }
  }

  activationText(plan: FuturesPlanCard): string {
    const arm = this.chosenArm(plan);
    if (!arm) return '';
    const dir = arm.direction === 'LONG' ? 'above' : 'below';
    return `Activates when price closes ${dir} ${this.fmt(arm.entry)} on 2 × 5-min candles.`;
  }

  fmt(n: number | null | undefined): string {
    return n == null ? '—' : n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  }

  rupees(n: number | null | undefined): string {
    return n == null ? '—' : '₹' + Math.round(n).toLocaleString('en-IN');
  }

  trackPlan = (_: number, plan: FuturesPlanCard): string => plan.planId;
}
