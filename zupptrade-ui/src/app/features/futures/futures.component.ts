import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Agent1Signal } from '../../core/models/agent1-signal.model';
import { FuturesArmCard, FuturesPlanCard } from '../../core/models/futures.model';
import { FutureArmType, OpenZone } from '../../core/models/enums';
import { FuturesService } from '../../core/services/futures.service';
import { UserStateService } from '../../core/services/user-state.service';
import { BiasPillComponent } from '../../shared/components/bias-pill/bias-pill.component';
import { ConfidencePillComponent } from '../../shared/components/confidence-pill/confidence-pill.component';
import { MetricBoxComponent } from '../../shared/components/metric-box/metric-box.component';
import { catchError, of } from 'rxjs';

type PanelState = 'awaiting' | 'loading' | 'card' | 'submitting' | 'armed' | 'error';

@Component({
  selector: 'app-futures',
  standalone: true,
  imports: [
    CommonModule, MatButtonModule, MatProgressSpinnerModule,
    BiasPillComponent, ConfidencePillComponent, MetricBoxComponent,
  ],
  templateUrl: './futures.component.html',
  styleUrls: ['./futures.component.scss'],
})
export class FuturesComponent {
  @Input() signal: Agent1Signal | null = null;

  state: PanelState = 'awaiting';
  card: FuturesPlanCard | null = null;
  errorMessage: string | null = null;
  armedArm: FuturesArmCard | null = null;

  constructor(
    private readonly futures: FuturesService,
    private readonly userState: UserStateService,
  ) {}

  // ── Actions ────────────────────────────────────────────────────────────

  getPlan(): void {
    const userProfileId = this.userState.userProfileId;
    if (!userProfileId) {
      this.errorMessage = 'No user profile loaded.';
      this.state = 'error';
      return;
    }

    // Backend regenerates the Agent 1 signal from the mandatory admin commentary — no need for a
    // pre-existing Trading-tab signal. agent1SignalId is sent when available, but is optional.
    this.state = 'loading';
    this.errorMessage = null;
    this.futures
      .recommend({ agent1SignalId: this.signal?.id, userProfileId, runPhase: 900 })
      .pipe(catchError(err => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to build futures plan.';
        this.state = 'error';
        return of(null);
      }))
      .subscribe(card => {
        if (card) {
          this.card = card;
          this.state = 'card';
        }
      });
  }

  chooseArm(arm: FuturesArmCard): void {
    if (!this.card || !this.isSelectable(arm)) return;
    this.state = 'submitting';
    this.futures
      .confirm({ planId: this.card.planId, action: 'CONFIRM', selectedArm: arm.armType })
      .pipe(catchError(err => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to arm the trade.';
        this.state = 'error';
        return of(null);
      }))
      .subscribe(updated => {
        if (updated) {
          this.card = updated;
          this.armedArm = arm;
          this.state = 'armed';
        }
      });
  }

  rejectPlan(): void {
    if (!this.card) return;
    this.state = 'submitting';
    this.futures
      .confirm({ planId: this.card.planId, action: 'REJECT' })
      .pipe(catchError(() => of(null)))
      .subscribe(() => this.reset());
  }

  reset(): void {
    this.card = null;
    this.armedArm = null;
    this.errorMessage = null;
    this.state = 'awaiting';
  }

  // ── View helpers ───────────────────────────────────────────────────────

  isSelectable(arm: FuturesArmCard): boolean {
    return arm.status !== 'BLOCKED' && !this.isMissed(arm) && this.card?.status === 'PRIMED';
  }

  /** True when the live level has left this arm's stop→target band — its entry won't come. */
  isMissed(arm: FuturesArmCard): boolean {
    return arm.reachability === 'MISSED';
  }

  /** Plain-English activation condition shown on a chosen trade. */
  activationText(arm: FuturesArmCard | null): string {
    if (!arm) return '';
    const dir = arm.direction === 'LONG' ? 'above' : 'below';
    return `Activates when price closes ${dir} ${this.fmt(arm.entry)} (2 × 5-min candles).`;
  }

  openZoneLabel(zone: OpenZone): string {
    switch (zone) {
      case 'BREAKOUT': return 'Opened above the range';
      case 'BREAKDOWN': return 'Opened below the range';
      default: return 'Opened inside the range';
    }
  }

  armCssClass(arm: FuturesArmCard): string {
    const base = `arm arm-${arm.status.toLowerCase()}`;
    return this.isMissed(arm) ? `${base} arm-missed` : base;
  }

  statusBadge(arm: FuturesArmCard): string {
    switch (arm.status) {
      case 'RECOMMENDED': return 'Recommended';
      case 'ALLOWED': return 'Available';
      default: return 'Not available';
    }
  }

  trackArm = (_: number, arm: FuturesArmCard): FutureArmType => arm.armType;

  fmt(n: number | null | undefined): string {
    return n == null ? '—' : n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  }

  rupees(n: number | null | undefined): string {
    return n == null ? '—' : '₹' + Math.round(n).toLocaleString('en-IN');
  }
}
