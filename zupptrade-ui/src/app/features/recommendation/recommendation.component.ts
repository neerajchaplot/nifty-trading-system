import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Agent1Signal } from '../../core/models/agent1-signal.model';
import { TradeCard } from '../../core/models/trade.model';
import { Agent2Service } from '../../core/services/agent2.service';
import { BiasPillComponent } from '../../shared/components/bias-pill/bias-pill.component';
import { ConfidencePillComponent } from '../../shared/components/confidence-pill/confidence-pill.component';
import { GateBadgeComponent } from '../../shared/components/gate-badge/gate-badge.component';
import { MetricBoxComponent } from '../../shared/components/metric-box/metric-box.component';
import { catchError, of } from 'rxjs';

type PanelState = 'ready' | 'loading' | 'tradecard' | 'rejected' | 'active';

const DEFAULT_USER_PROFILE_ID = '00000000-0000-0000-0000-000000000001';

@Component({
  selector: 'app-recommendation',
  standalone: true,
  imports: [
    CommonModule, MatButtonModule, MatProgressSpinnerModule,
    BiasPillComponent, ConfidencePillComponent, GateBadgeComponent, MetricBoxComponent,
  ],
  templateUrl: './recommendation.component.html',
  styleUrls: ['./recommendation.component.scss'],
})
export class RecommendationComponent implements OnChanges {
  @Input() signal: Agent1Signal | null = null;

  state: PanelState = 'ready';
  tradeCard: TradeCard | null = null;
  errorMessage: string | null = null;

  constructor(private agent2: Agent2Service) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['signal'] && this.state === 'ready') {
      // Nothing to do — keep ready state, just update displayed signal
    }
  }

  get zoneTitle(): string {
    if (!this.signal) return 'Recommendation Engine';
    return `${this.signal.bias} · ${this.signal.strength} · ${this.signal.confidence}`;
  }

  get expiryLabel(): string {
    if (!this.tradeCard) return '';
    return `Expiry ${this.tradeCard.expiryDate} (DTE ${this.tradeCard.dte})`;
  }

  generateRecommendation(): void {
    if (!this.signal) return;
    this.state = 'loading';
    this.errorMessage = null;

    this.agent2.recommend({
      agent1SignalId: this.signal.id,
      userProfileId: DEFAULT_USER_PROFILE_ID,
    }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Failed to generate recommendation. Please try again.';
        this.state = 'ready';
        return of(null);
      })
    ).subscribe(card => {
      if (!card) return;
      this.tradeCard = card;
      this.state = card.status === 'REJECTED' ? 'rejected' : 'tradecard';
    });
  }

  confirmTrade(): void {
    if (!this.tradeCard) return;
    this.agent2.confirm({ tradeId: this.tradeCard.tradeId, action: 'CONFIRM' }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Confirm failed.';
        return of(null);
      })
    ).subscribe(card => {
      if (card) {
        this.tradeCard = card;
        this.state = 'active';
      }
    });
  }

  rejectTrade(): void {
    if (!this.tradeCard) return;
    this.agent2.confirm({ tradeId: this.tradeCard.tradeId, action: 'REJECT' }).pipe(
      catchError(() => of(null))
    ).subscribe(() => {
      this.state = 'rejected';
    });
  }

  reset(): void {
    this.state = 'ready';
    this.tradeCard = null;
    this.errorMessage = null;
  }

  gateState(passed: boolean, isIndicative: boolean): 'pass' | 'fail' | 'info' {
    if (isIndicative) return 'info';
    return passed ? 'pass' : 'fail';
  }

  strategyLabel(s: string): string {
    return s.replace(/_/g, ' ');
  }

  formatInr(val: number | null | undefined): string {
    if (val == null) return '—';
    return `₹ ${val.toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
  }

  formatPct(val: number | null | undefined): string {
    if (val == null) return '—';
    return `${val.toFixed(1)}%`;
  }
}
