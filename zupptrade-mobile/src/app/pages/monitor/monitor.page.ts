import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonHeader, IonToolbar, IonTitle, IonContent, IonRefresher,
  IonRefresherContent, IonCard, IonCardContent, IonBadge,
} from '@ionic/angular/standalone';
import { DashboardStateService } from '../../core/services/dashboard-state.service';
import { ActiveTrade } from '../../core/models/trade.model';

@Component({
  selector: 'app-monitor',
  standalone: true,
  imports: [
    CommonModule,
    IonHeader, IonToolbar, IonTitle, IonContent, IonRefresher,
    IonRefresherContent, IonCard, IonCardContent, IonBadge,
  ],
  template: `
    <ion-header>
      <ion-toolbar>
        <ion-title>Monitor</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      <ion-refresher slot="fixed" (ionRefresh)="doRefresh($event)">
        <ion-refresher-content></ion-refresher-content>
      </ion-refresher>

      <ng-container *ngIf="activeTrades$ | async as trades">

        <div *ngIf="trades.length === 0" class="empty-state">
          <div class="empty-icon">📡</div>
          <div class="empty-title">No Active Trades</div>
          <div class="empty-sub">Confirmed trades appear here for real-time monitoring.</div>
        </div>

        <ion-card *ngFor="let trade of trades">
          <ion-card-content>

            <!-- Header row -->
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
              <div>
                <div style="font-size:13px; font-weight:800;">{{ trade.tradeCode }}</div>
                <div style="font-size:11px; color:var(--zt-muted);">Expiry {{ trade.expiryDate }}</div>
              </div>
              <div style="display:flex; gap:6px; flex-direction:column; align-items:flex-end;">
                <ion-badge [color]="statusColor(trade.status)">{{ trade.status }}</ion-badge>
                <ion-badge *ngIf="trade.lastThresholdHit && trade.lastThresholdHit !== 'NONE'"
                           [color]="thresholdColor(trade.lastThresholdHit)">
                  {{ trade.lastThresholdHit }} HIT
                </ion-badge>
              </div>
            </div>

            <!-- Alert banners -->
            <div *ngIf="trade.lastThresholdHit === 'T3'" class="alert-t3">⚠ T3 EXIT — Close all positions now!</div>
            <div *ngIf="trade.lastThresholdHit === 'T2'" class="alert-t2">⚡ T2 READJUST — Consider rolling the spread</div>
            <div *ngIf="trade.lastThresholdHit === 'T1'" class="alert-t1">👀 T1 WATCH — Monitor closely</div>

            <!-- Live metrics -->
            <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:8px; margin-top:10px;">
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">Spot</div>
                <div style="font-size:14px; font-weight:700;">{{ trade.spotPrice | number:'1.0-0' }}</div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">VIX</div>
                <div style="font-size:14px; font-weight:700;">{{ trade.vixLevel | number:'1.2-2' }}</div>
              </div>
              <div>
                <div style="font-size:9px; color:var(--zt-muted); text-transform:uppercase;">P&L</div>
                <div style="font-size:14px; font-weight:700;" [class]="pnlClass(trade)">
                  {{ trade.markToMarketPnl | number:'1.0-0' }}
                </div>
              </div>
            </div>

            <!-- Threshold progress bars (credit spreads) -->
            <ng-container *ngIf="trade.monitorConfig && trade.spotPrice">
              <ng-container *ngIf="trade.monitorConfig.thresholds.t1WatchNiftyLevel as t1">
                <div style="margin-top:12px;">
                  <div class="divider-label">Threshold Distance</div>
                  <div *ngIf="trade.monitorConfig.spreadDirection === 'CREDIT'">
                    <div style="font-size:11px; color:var(--zt-sub); margin-bottom:4px;">
                      Short strike: {{ trade.monitorConfig.shortLeg.strike }}
                    </div>
                    <div style="background:var(--zt-border); border-radius:4px; height:6px; overflow:hidden;">
                      <div [style.width.%]="pctToT3(trade)" [style.background]="barColor(trade)"
                           style="height:100%; border-radius:4px; transition:width 0.4s;"></div>
                    </div>
                    <div style="display:flex; justify-content:space-between; font-size:10px; color:var(--zt-muted); margin-top:2px;">
                      <span class="level-t3">T3 {{ trade.monitorConfig.thresholds.t3ExitNiftyLevel | number:'1.0-0' }}</span>
                      <span>Spot {{ trade.spotPrice | number:'1.0-0' }}</span>
                    </div>
                  </div>
                </div>
              </ng-container>
            </ng-container>

            <!-- Monitor action -->
            <div style="margin-top:8px; font-size:11px; color:var(--zt-muted);">
              Action: <strong [class]="actionClass(trade.lastAction)">{{ trade.lastAction ?? 'PENDING' }}</strong>
              <span *ngIf="trade.lastEvaluatedAt"> · {{ trade.lastEvaluatedAt | date:'HH:mm:ss' }}</span>
            </div>

          </ion-card-content>
        </ion-card>

      </ng-container>
    </ion-content>
  `,
})
export class MonitorPage implements OnInit {
  private state = inject(DashboardStateService);
  activeTrades$ = this.state.activeTrades;

  ngOnInit(): void {}

  doRefresh(event: CustomEvent): void {
    this.state.refreshTrades();
    setTimeout(() => (event.target as HTMLIonRefresherElement).complete(), 1200);
  }

  statusColor(status: string): string {
    if (status === 'ACTIVE') return 'success';
    if (status === 'EXIT_IN_PROGRESS') return 'warning';
    if (status === 'EXIT_FAILED') return 'danger';
    return 'medium';
  }

  thresholdColor(t: string): string {
    if (t === 'T3') return 'danger';
    if (t === 'T2') return 'warning';
    return 'primary';
  }

  pnlClass(trade: ActiveTrade): string {
    const pnl = trade.markToMarketPnl ?? 0;
    if (pnl > 0) return 'text-green';
    if (pnl < 0) return 'text-red';
    return '';
  }

  actionClass(action: string | null): string {
    if (action === 'EXIT') return 'text-red fw-800';
    if (action === 'READJUST') return 'text-amber fw-800';
    if (action === 'WATCH') return 'text-blue';
    return '';
  }

  pctToT3(trade: ActiveTrade): number {
    const spot = trade.spotPrice ?? 0;
    const t3 = trade.monitorConfig?.thresholds.t3ExitNiftyLevel ?? 0;
    const short = trade.monitorConfig?.shortLeg.strike ?? 0;
    if (!short || !t3 || short === t3) return 0;
    // 0% = at short strike, 100% = at T3
    const progress = (spot - short) / (t3 - short) * 100;
    return Math.max(0, Math.min(100, progress));
  }

  barColor(trade: ActiveTrade): string {
    const pct = this.pctToT3(trade);
    if (pct >= 80) return '#DC2626';
    if (pct >= 50) return '#D97706';
    return '#16A34A';
  }
}
