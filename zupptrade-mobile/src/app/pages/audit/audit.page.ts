import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonHeader, IonToolbar, IonTitle, IonContent, IonRefresher,
  IonRefresherContent, IonCard, IonCardContent, IonBadge,
  IonSkeletonText, IonButtons, IonSegment, IonSegmentButton, IonLabel,
} from '@ionic/angular/standalone';
import { AppHeaderComponent } from '../../shared/components/app-header/app-header.component';
import { AuditService } from '../../core/services/audit.service';
import { PortfolioSummary, TradeListItem, TradeListResponse } from '../../core/models/audit.models';
import { FuturesAnalyticsService } from '../../core/services/futures-analytics.service';
import { FuturesPnlResponse } from '../../core/models/futures-analytics.model';

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    CommonModule, AppHeaderComponent,
    IonHeader, IonToolbar, IonTitle, IonContent, IonRefresher,
    IonRefresherContent, IonCard, IonCardContent, IonBadge,
    IonSkeletonText, IonButtons, IonSegment, IonSegmentButton, IonLabel,
  ],
  template: `
    <app-header title="Audit"></app-header>

    <ion-content class="ion-padding">
      <ion-refresher slot="fixed" (ionRefresh)="doRefresh($event)">
        <ion-refresher-content></ion-refresher-content>
      </ion-refresher>

      <ion-segment [value]="mode" (ionChange)="onSegment($any($event.detail.value))" style="margin-bottom:12px;">
        <ion-segment-button value="options"><ion-label>Options</ion-label></ion-segment-button>
        <ion-segment-button value="futures"><ion-label>Futures</ion-label></ion-segment-button>
      </ion-segment>

      <ng-container *ngIf="mode === 'options'">

      <!-- Loading -->
      <ion-card *ngIf="loading">
        <ion-card-content>
          <ion-skeleton-text [animated]="true" style="width:50%; height:22px; margin-bottom:8px;"></ion-skeleton-text>
          <ion-skeleton-text [animated]="true" style="width:80%; height:14px;"></ion-skeleton-text>
        </ion-card-content>
      </ion-card>

      <!-- Error -->
      <div *ngIf="error" class="error-banner">{{ error }}</div>

      <!-- Portfolio summary -->
      <ng-container *ngIf="summary">
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Portfolio Summary</div>
            <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:10px; margin-top:4px;">
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Trades</div>
                <div style="font-size:18px; font-weight:800;">{{ summary.totalTrades }}</div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Win Rate</div>
                <div style="font-size:18px; font-weight:800; color:var(--zt-green);">{{ summary.winRatePct | number:'1.1-1' }}%</div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Realized P&L</div>
                <div style="font-size:18px; font-weight:800;" [class]="summary.totalRealizedPnl >= 0 ? 'text-green' : 'text-red'">
                  ₹{{ summary.totalRealizedPnl | number:'1.0-0' }}
                </div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Avg RoC</div>
                <div style="font-size:14px; font-weight:700;">{{ summary.avgRocAchievedPct | number:'1.2-2' }}%</div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Signal Acc.</div>
                <div style="font-size:14px; font-weight:700;">{{ summary.agent1AccuracyPct | number:'1.1-1' }}%</div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Max Drawdown</div>
                <div style="font-size:14px; font-weight:700; color:var(--zt-red);">₹{{ summary.maxDrawdown | number:'1.0-0' }}</div>
              </div>
            </div>
          </ion-card-content>
        </ion-card>
      </ng-container>

      <!-- Trade list -->
      <ng-container *ngIf="trades.length > 0">
        <div class="divider-label" style="margin:8px 4px 4px;">Trade History</div>
        <ion-card *ngFor="let t of trades">
          <ion-card-content>
            <div style="display:flex; justify-content:space-between; align-items:flex-start;">
              <div>
                <div style="font-size:13px; font-weight:800; margin-bottom:2px;">{{ t.tradeCode }}</div>
                <div style="font-size:11px; color:var(--zt-muted);">{{ t.strategy }} · {{ t.expiryDate }}</div>
              </div>
              <ion-badge [color]="outcomeColor(t.outcome)">{{ t.outcome }}</ion-badge>
            </div>
            <div style="display:flex; justify-content:space-between; margin-top:8px; font-size:12px;">
              <span style="color:var(--zt-sub);">{{ t.lots }} lots · {{ t.signalBias }} {{ t.signalStrength }}</span>
              <span [class]="t.actualPnl >= 0 ? 'text-green fw-800' : 'text-red fw-800'">
                ₹{{ t.actualPnl | number:'1.0-0' }}
              </span>
            </div>
            <div *ngIf="t.closeReason" style="font-size:11px; color:var(--zt-muted); margin-top:4px;">
              {{ t.closeReason }}
            </div>
          </ion-card-content>
        </ion-card>
      </ng-container>

      <!-- Empty: no closed trades -->
      <div *ngIf="!loading && !error && trades.length === 0 && !summary?.totalTrades" class="empty-state">
        <div class="empty-icon">📋</div>
        <div class="empty-title">No Closed Trades</div>
        <div class="empty-sub">Completed trades will appear here for performance analysis.</div>
      </div>

      <!-- Summary loaded but 0 trades -->
      <div *ngIf="!loading && !error && summary && summary.totalTrades === 0 && trades.length === 0" class="empty-state">
        <div class="empty-icon">📋</div>
        <div class="empty-title">No Trade History Yet</div>
        <div class="empty-sub">Go to Trade tab, confirm a position, and let it close to see analytics here.</div>
      </div>

      </ng-container>

      <!-- ═══════════ FUTURES ═══════════ -->
      <ng-container *ngIf="mode === 'futures'">
        <div *ngIf="futuresError" class="error-banner">{{ futuresError }}</div>

        <ng-container *ngIf="futures as f">
          <ion-card>
            <ion-card-content>
              <div class="divider-label">Futures P&L</div>
              <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:10px; margin-top:4px;">
                <div>
                  <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Trades</div>
                  <div style="font-size:18px; font-weight:800;">{{ f.summary.tradeCount }}</div>
                </div>
                <div>
                  <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Win / Loss</div>
                  <div style="font-size:18px; font-weight:800;">{{ f.summary.winCount }}/{{ f.summary.lossCount }}</div>
                </div>
                <div>
                  <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Realized P&L</div>
                  <div style="font-size:18px; font-weight:800;" [class]="f.summary.totalRealizedPnl >= 0 ? 'text-green' : 'text-red'">
                    ₹{{ f.summary.totalRealizedPnl | number:'1.0-0' }}
                  </div>
                </div>
              </div>
            </ion-card-content>
          </ion-card>

          <ng-container *ngIf="f.trades.length > 0">
            <div class="divider-label" style="margin:8px 4px 4px;">Closed Futures Trades</div>
            <ion-card *ngFor="let t of f.trades">
              <ion-card-content>
                <div style="display:flex; justify-content:space-between; align-items:flex-start;">
                  <div>
                    <div style="font-size:13px; font-weight:800; margin-bottom:2px;">{{ t.planCode }}</div>
                    <div style="font-size:11px; color:var(--zt-muted);">{{ t.direction }} · {{ t.tradeDate }}</div>
                  </div>
                  <span [class]="t.realizedPnl >= 0 ? 'text-green fw-800' : 'text-red fw-800'">
                    ₹{{ t.realizedPnl | number:'1.0-0' }}
                  </span>
                </div>
                <div style="display:flex; gap:12px; margin-top:6px; font-size:11px; color:var(--zt-sub);">
                  <span>Entry {{ t.entry | number:'1.0-0' }}</span>
                  <span>Stop {{ t.stop | number:'1.0-0' }}</span>
                  <span>Target {{ t.target | number:'1.0-0' }}</span>
                </div>
                <div *ngIf="t.closeReason" style="font-size:11px; color:var(--zt-muted); margin-top:4px;">{{ t.closeReason }}</div>
              </ion-card-content>
            </ion-card>
          </ng-container>

          <div *ngIf="f.trades.length === 0" class="empty-state">
            <div class="empty-icon">📋</div>
            <div class="empty-title">No Closed Futures Trades</div>
            <div class="empty-sub">Closed intraday futures trades will appear here.</div>
          </div>
        </ng-container>
      </ng-container>

    </ion-content>
  `,
})
export class AuditPage implements OnInit {
  private auditSvc = inject(AuditService);
  private futuresSvc = inject(FuturesAnalyticsService);

  mode: 'options' | 'futures' = 'options';

  summary: PortfolioSummary | null = null;
  trades: TradeListItem[] = [];
  loading = false;
  error: string | null = null;

  futures: FuturesPnlResponse | null = null;
  futuresError: string | null = null;

  ngOnInit(): void { this.load(); }

  onSegment(value: 'options' | 'futures'): void {
    this.mode = value;
    if (value === 'futures' && !this.futures) this.loadFutures();
  }

  private loadFutures(): void {
    this.futuresError = null;
    this.futuresSvc.getFuturesPnl().subscribe({
      next: r => { this.futures = r; },
      error: err => { this.futuresError = err?.error?.detail ?? 'Failed to load futures P&L.'; },
    });
  }

  doRefresh(event: CustomEvent): void {
    if (this.mode === 'futures') this.loadFutures();
    else this.load();
    setTimeout(() => (event.target as HTMLIonRefresherElement).complete(), 1500);
  }

  private load(): void {
    this.loading = true;
    this.error = null;

    this.auditSvc.getSummary().subscribe({
      next: s => { this.summary = s; this.loading = false; },
      error: err => { this.error = err?.error?.detail ?? 'Failed to load summary.'; this.loading = false; },
    });

    this.auditSvc.getTrades(0, 20).subscribe({
      next: (r: TradeListResponse) => { this.trades = r.trades; },
      error: () => {},
    });
  }

  outcomeColor(outcome: string): string {
    if (outcome === 'WIN') return 'success';
    if (outcome === 'LOSS') return 'danger';
    return 'medium';
  }
}
