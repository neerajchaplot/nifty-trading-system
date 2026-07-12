import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonHeader, IonToolbar, IonTitle, IonContent, IonRefresher,
  IonRefresherContent, IonCard, IonCardContent, IonBadge,
  IonSkeletonText, IonButton, IonIcon, IonButtons,
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { refreshOutline, trendingUpOutline, trendingDownOutline, removeOutline } from 'ionicons/icons';
import { DashboardStateService } from '../../core/services/dashboard-state.service';
import { Agent1Service } from '../../core/services/agent1.service';
import { Agent1Signal } from '../../core/models/agent1-signal.model';

@Component({
  selector: 'app-signal',
  standalone: true,
  imports: [
    CommonModule,
    IonHeader, IonToolbar, IonTitle, IonContent, IonRefresher,
    IonRefresherContent, IonCard, IonCardContent, IonBadge,
    IonSkeletonText, IonButton, IonIcon, IonButtons,
  ],
  template: `
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <img src="assets/icon-transparent-512.png" alt="ZuppTrade" style="height:26px;width:26px;margin-left:6px;">
        </ion-buttons>
        <ion-title>Market Signal</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">
      <ion-refresher slot="fixed" (ionRefresh)="doRefresh($event)">
        <ion-refresher-content></ion-refresher-content>
      </ion-refresher>

      <!-- Loading skeleton -->
      <ng-container *ngIf="loading$ | async">
        <ion-card>
          <ion-card-content>
            <ion-skeleton-text [animated]="true" style="width: 60%; height: 28px; margin-bottom: 8px;"></ion-skeleton-text>
            <ion-skeleton-text [animated]="true" style="width: 40%; height: 18px;"></ion-skeleton-text>
          </ion-card-content>
        </ion-card>
      </ng-container>

      <!-- Error state -->
      <div *ngIf="(error$ | async) as err" class="error-banner">{{ err }}</div>

      <!-- Signal card -->
      <ng-container *ngIf="signal$ | async as signal">

        <!-- Hero bias card -->
        <ion-card [class]="'bias-card ' + biasClass(signal)">
          <ion-card-content>
            <div style="display:flex; align-items:center; gap:10px; margin-bottom:4px;">
              <ion-icon [name]="biasIcon(signal)" style="font-size:28px;"></ion-icon>
              <div>
                <div style="font-size:22px; font-weight:800; text-transform:capitalize;">
                  {{ signal.bias | titlecase }} {{ signal.strength | titlecase }}
                </div>
                <div style="font-size:12px; opacity:0.7;">Score: {{ signal.compositeScore | number:'1.3-3' }}</div>
              </div>
              <ion-badge slot="end" [color]="confColor(signal.confidence)" style="margin-left:auto;">
                {{ signal.confidence }}
              </ion-badge>
            </div>
            <div style="font-size:11px; opacity:0.65; margin-top:4px;">
              Expiry {{ signal.expiryDate }} &nbsp;|&nbsp; {{ signal.timestamp | date:'dd MMM HH:mm' }}
            </div>
          </ion-card-content>
        </ion-card>

        <!-- VIX card -->
        <ion-card *ngIf="signal.vixLevel">
          <ion-card-content>
            <div class="divider-label">Volatility</div>
            <div style="display:flex; gap:16px;">
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">VIX</div>
                <div style="font-size:22px; font-weight:800;" [class]="vixClass(signal)">
                  {{ signal.vixLevel | number:'1.2-2' }}
                </div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">Regime</div>
                <div style="font-size:14px; font-weight:700; margin-top:4px;">{{ signal.vixRegime }}</div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">Direction</div>
                <div style="font-size:14px; font-weight:700; margin-top:4px;">{{ signal.vixDirection }}</div>
              </div>
            </div>
          </ion-card-content>
        </ion-card>

        <!-- Score breakdown -->
        <ng-container *ngIf="breakdown(signal) as bd">
          <ion-card *ngIf="bd">
            <ion-card-content>
              <div class="divider-label">Score Breakdown</div>
              <div *ngFor="let tier of tierEntries(bd)"
                   style="display:flex; justify-content:space-between; margin:6px 0; font-size:13px;">
                <span style="color:var(--zt-sub);">{{ tierLabel(tier.key) }}</span>
                <span [style.color]="tier.value >= 0 ? 'var(--zt-green)' : 'var(--zt-red)'" style="font-weight:700;">
                  {{ tier.value > 0 ? '+' : '' }}{{ tier.value | number:'1.3-3' }}
                </span>
              </div>
            </ion-card-content>
          </ion-card>
        </ng-container>

        <!-- Refresh button -->
        <ion-button expand="block" fill="outline" (click)="rescore()" style="margin-top:8px;">
          <ion-icon name="refresh-outline" slot="start"></ion-icon>
          Re-score Now
        </ion-button>

      </ng-container>

      <!-- Empty state (no signal and not loading) -->
      <div *ngIf="!(signal$ | async) && !(loading$ | async)" class="empty-state">
        <div class="empty-icon">📡</div>
        <div class="empty-title">No Signal Yet</div>
        <div class="empty-sub">Pull down to refresh or tap Re-score to fetch today's signal.</div>
        <ion-button (click)="rescore()" style="margin-top:12px;">Re-score</ion-button>
      </div>

    </ion-content>
  `,
  styles: [`
    .bias-card { --background: transparent; }
    .bias-bullish { --background: rgba(22,163,74,0.08); border: 1px solid rgba(22,163,74,0.25); border-radius: 12px; }
    .bias-bearish { --background: rgba(220,38,38,0.08); border: 1px solid rgba(220,38,38,0.25); border-radius: 12px; }
    .bias-neutral { --background: rgba(37,99,235,0.06); border: 1px solid rgba(37,99,235,0.20); border-radius: 12px; }
    .vix-low    { color: var(--zt-green); }
    .vix-normal { color: var(--zt-blue); }
    .vix-high   { color: var(--zt-amber); }
    .vix-extreme{ color: var(--zt-red); }
  `],
})
export class SignalPage implements OnInit {
  private state = inject(DashboardStateService);
  private agent1 = inject(Agent1Service);

  constructor() {
    addIcons({ refreshOutline, trendingUpOutline, trendingDownOutline, removeOutline });
  }

  signal$ = this.state.signal;
  loading$ = this.state.signalLoading;
  error$ = this.state.signalError;

  ngOnInit(): void {}

  doRefresh(event: CustomEvent): void {
    this.state.refreshSignal();
    setTimeout(() => (event.target as HTMLIonRefresherElement).complete(), 1500);
  }

  rescore(): void { this.state.refreshSignal(); }

  biasClass(signal: Agent1Signal): string {
    return `bias-${signal.bias.toLowerCase()}`;
  }

  biasIcon(signal: Agent1Signal): string {
    if (signal.bias === 'BULLISH') return 'trending-up-outline';
    if (signal.bias === 'BEARISH') return 'trending-down-outline';
    return 'remove-outline';
  }

  confColor(conf: string): string {
    if (conf === 'HIGH') return 'success';
    if (conf === 'MEDIUM') return 'warning';
    return 'medium';
  }

  vixClass(signal: Agent1Signal): string {
    const regime = signal.vixRegime?.toLowerCase() ?? 'normal';
    return `vix-${regime}`;
  }

  breakdown(signal: Agent1Signal): Record<string, number> | null {
    if (!signal.scoreBreakdown) return null;
    try { return JSON.parse(signal.scoreBreakdown); } catch { return null; }
  }

  tierEntries(bd: Record<string, number>): { key: string; value: number }[] {
    return Object.entries(bd).map(([key, value]) => ({ key, value }));
  }

  tierLabel(key: string): string {
    const map: Record<string, string> = {
      tier1a: 'Price Structure (30%)',
      tier1b: 'Technical (20%)',
      tier2: 'Institutional Flow (30%)',
      tier3: 'Volatility/Macro (10%)',
      tier4: 'Commentary/News (10%)',
    };
    return map[key] ?? key;
  }
}
