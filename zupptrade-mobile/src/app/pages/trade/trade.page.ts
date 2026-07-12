import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonHeader, IonToolbar, IonTitle, IonContent, IonCard, IonCardContent,
  IonButton, IonIcon, IonBadge, IonSpinner, IonAlert, IonButtons,
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { checkmarkCircleOutline, closeCircleOutline, refreshOutline } from 'ionicons/icons';
import { DashboardStateService } from '../../core/services/dashboard-state.service';
import { Agent2Service } from '../../core/services/agent2.service';
import { UserStateService } from '../../core/services/user-state.service';
import { TradeCard } from '../../core/models/trade.model';
import { Agent1Signal } from '../../core/models/agent1-signal.model';

const DEFAULT_USER_PROFILE_ID = '90412ca3-1e3f-4c75-9444-ca1ebfd92348';

type PageState = 'ready' | 'loading' | 'tradecard' | 'active' | 'skip';

@Component({
  selector: 'app-trade',
  standalone: true,
  imports: [
    CommonModule,
    IonHeader, IonToolbar, IonTitle, IonContent, IonCard, IonCardContent,
    IonButton, IonIcon, IonBadge, IonSpinner, IonAlert, IonButtons,
  ],
  template: `
    <ion-header>
      <ion-toolbar style="--background:#ffffff; --border-color:#E2E8F0;">
        <ion-buttons slot="start">
          <img src="assets/zupp-logo.jpg" alt="ZuppTrade" style="height:32px;width:auto;margin-left:8px;object-fit:contain;">
        </ion-buttons>
        <ion-title style="color:#1B4FA8;">Trade</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content class="ion-padding">

      <!-- READY state: show signal summary + Generate button -->
      <ng-container *ngIf="pageState === 'ready'">
        <ng-container *ngIf="signal$ | async as signal">
          <ion-card>
            <ion-card-content>
              <div class="divider-label">Current Signal</div>
              <div style="display:flex; align-items:center; gap:8px; margin-bottom:12px;">
                <span style="font-size:18px; font-weight:800;">{{ signal.bias | titlecase }} {{ signal.strength | titlecase }}</span>
                <ion-badge [color]="confColor(signal.confidence)">{{ signal.confidence }}</ion-badge>
              </div>
              <div style="font-size:12px; color:var(--zt-muted);">Score {{ signal.compositeScore | number:'1.3-3' }} &nbsp;·&nbsp; VIX {{ signal.vixLevel | number:'1.1-1' }}</div>
            </ion-card-content>
          </ion-card>
          <ion-button expand="block" (click)="generate(signal)" [disabled]="signal.bias === 'NEUTRAL' && signal.strength === 'WEAK'">
            Generate Recommendation
          </ion-button>
          <div *ngIf="signal.bias === 'NEUTRAL' && signal.strength === 'WEAK'"
               style="font-size:12px; color:var(--zt-muted); text-align:center; margin-top:8px;">
            Signal too weak for trade — no action recommended
          </div>
        </ng-container>

        <div *ngIf="!(signal$ | async)" class="empty-state">
          <div class="empty-icon">📊</div>
          <div class="empty-title">No Signal</div>
          <div class="empty-sub">Go to Signal tab to fetch today's market signal first.</div>
        </div>
      </ng-container>

      <!-- LOADING state -->
      <div *ngIf="pageState === 'loading'" class="empty-state">
        <ion-spinner name="crescent"></ion-spinner>
        <div class="empty-title" style="margin-top:16px;">Getting Recommendation…</div>
      </div>

      <!-- SKIP state -->
      <div *ngIf="pageState === 'skip'" class="empty-state">
        <div class="empty-icon">⏭️</div>
        <div class="empty-title">No Trade Today</div>
        <div class="empty-sub">{{ skipReason }}</div>
        <ion-button fill="outline" (click)="reset()" style="margin-top:16px;">
          <ion-icon name="refresh-outline" slot="start"></ion-icon>
          Try Again
        </ion-button>
      </div>

      <!-- TRADECARD state -->
      <ng-container *ngIf="pageState === 'tradecard' && tradeCard">

        <!-- Strategy header -->
        <ion-card>
          <ion-card-content>
            <div style="display:flex; align-items:center; gap:8px; margin-bottom:8px;">
              <span style="font-size:16px; font-weight:800;">{{ strategyLabel(tradeCard.strategy) }}</span>
              <ion-badge [color]="tradeCard.spreadDirection === 'CREDIT' ? 'success' : 'primary'">
                {{ tradeCard.spreadDirection }}
              </ion-badge>
            </div>
            <div style="font-size:12px; color:var(--zt-muted);">
              Expiry {{ tradeCard.expiryDate }} · DTE {{ tradeCard.dte }} days
            </div>
          </ion-card-content>
        </ion-card>

        <!-- Legs -->
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Legs</div>
            <div style="display:flex; gap:8px;">
              <div class="leg-card leg-sell">
                <div class="leg-action">SELL</div>
                <div class="leg-strike">{{ tradeCard.shortLeg.strike }}</div>
                <div class="leg-meta">{{ tradeCard.shortLeg.optionType }} · ₹{{ tradeCard.shortLeg.ltp | number:'1.2-2' }}</div>
              </div>
              <div class="leg-card leg-buy">
                <div class="leg-action">BUY</div>
                <div class="leg-strike">{{ tradeCard.longLeg.strike }}</div>
                <div class="leg-meta">{{ tradeCard.longLeg.optionType }} · ₹{{ tradeCard.longLeg.ltp | number:'1.2-2' }}</div>
              </div>
            </div>
            <!-- Iron Condor CE legs -->
            <div *ngIf="tradeCard.shortLeg2" style="display:flex; gap:8px; margin-top:8px;">
              <div class="leg-card leg-sell">
                <div class="leg-action">SELL</div>
                <div class="leg-strike">{{ tradeCard.shortLeg2!.strike }}</div>
                <div class="leg-meta">{{ tradeCard.shortLeg2!.optionType }} · ₹{{ tradeCard.shortLeg2!.ltp | number:'1.2-2' }}</div>
              </div>
              <div class="leg-card leg-buy">
                <div class="leg-action">BUY</div>
                <div class="leg-strike">{{ tradeCard.longLeg2!.strike }}</div>
                <div class="leg-meta">{{ tradeCard.longLeg2!.optionType }} · ₹{{ tradeCard.longLeg2!.ltp | number:'1.2-2' }}</div>
              </div>
            </div>
          </ion-card-content>
        </ion-card>

        <!-- P&L summary -->
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Summary</div>
            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">Net Premium</div>
                <div style="font-size:16px; font-weight:800; color:var(--zt-green);">₹{{ tradeCard.netPremiumPerUnit | number:'1.2-2' }}</div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">Lots</div>
                <div style="font-size:16px; font-weight:800;">{{ tradeCard.lots }} × {{ tradeCard.lotSize }}</div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">Max Profit</div>
                <div style="font-size:15px; font-weight:700; color:var(--zt-green);">₹{{ tradeCard.maxProfitTotal | number:'1.0-0' }}</div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">Expected Loss</div>
                <div style="font-size:15px; font-weight:700; color:var(--zt-red);">₹{{ tradeCard.realExpectedLossTotal | number:'1.0-0' }}</div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">PoP</div>
                <div style="font-size:15px; font-weight:700;">{{ tradeCard.pop | number:'1.1-1' }}%</div>
              </div>
              <div>
                <div style="font-size:10px; color:var(--zt-muted); text-transform:uppercase;">RoC</div>
                <div style="font-size:15px; font-weight:700; color:var(--zt-blue);">{{ tradeCard.roc | number:'1.2-2' }}%</div>
              </div>
            </div>
          </ion-card-content>
        </ion-card>

        <!-- Gates -->
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Gate Checks</div>
            <div *ngFor="let g of tradeCard.gateResults"
                 style="display:flex; justify-content:space-between; align-items:center; padding:5px 0; border-bottom:1px solid var(--zt-border);">
              <span style="font-size:12px; color:var(--zt-sub);">{{ g.gate }}</span>
              <ion-badge [color]="g.passed ? 'success' : 'danger'">{{ g.passed ? 'PASS' : 'FAIL' }}</ion-badge>
            </div>
          </ion-card-content>
        </ion-card>

        <!-- Thresholds -->
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Monitoring Levels</div>
            <div style="display:flex; flex-direction:column; gap:6px; font-size:12px;">
              <div style="display:flex; justify-content:space-between;">
                <span class="level-t1">T1 Watch</span>
                <span>{{ tradeCard.thresholds.t1WatchNiftyLevel ?? tradeCard.thresholds.t1WatchNiftyDown | number:'1.0-0' }}</span>
              </div>
              <div style="display:flex; justify-content:space-between;">
                <span class="level-t2">T2 Readjust</span>
                <span>{{ tradeCard.thresholds.t2ReadjustNiftyLevel ?? tradeCard.thresholds.t2ReadjustNiftyDown | number:'1.0-0' }}</span>
              </div>
              <div style="display:flex; justify-content:space-between;">
                <span class="level-t3">T3 Exit</span>
                <span>{{ tradeCard.thresholds.t3ExitNiftyLevel ?? tradeCard.thresholds.t3ExitNiftyDown | number:'1.0-0' }}</span>
              </div>
            </div>
          </ion-card-content>
        </ion-card>

        <!-- Error -->
        <div *ngIf="error" class="error-banner">{{ error }}</div>

        <!-- CTA buttons -->
        <div style="display:flex; gap:10px; margin-top:8px;">
          <ion-button color="danger" fill="outline" expand="block" style="flex:1;" (click)="reject()">
            <ion-icon name="close-circle-outline" slot="start"></ion-icon>
            Reject
          </ion-button>
          <ion-button color="success" expand="block" style="flex:1;" (click)="confirm()">
            <ion-icon name="checkmark-circle-outline" slot="start"></ion-icon>
            Confirm
          </ion-button>
        </div>

        <ion-button expand="block" fill="clear" (click)="reset()" style="margin-top:4px;">
          New Recommendation
        </ion-button>
      </ng-container>

      <!-- ACTIVE state -->
      <div *ngIf="pageState === 'active'" class="empty-state" style="padding-top: 24px;">
        <div class="empty-icon">✅</div>
        <div class="empty-title">Trade Confirmed</div>
        <div class="empty-sub">Check the Monitor tab for live P&L and threshold alerts.</div>
        <ion-button fill="outline" (click)="reset()" style="margin-top:16px;">New Recommendation</ion-button>
      </div>

    </ion-content>
  `,
})
export class TradePage implements OnInit {
  private state = inject(DashboardStateService);
  private agent2 = inject(Agent2Service);
  private userState = inject(UserStateService);

  signal$ = this.state.signal;
  pageState: PageState = 'ready';
  tradeCard: TradeCard | null = null;
  error: string | null = null;
  skipReason: string | null = null;

  ngOnInit(): void {
    addIcons({ checkmarkCircleOutline, closeCircleOutline, refreshOutline });
  }

  generate(signal: Agent1Signal): void {
    const profileId = this.userState.userProfileId ?? DEFAULT_USER_PROFILE_ID;
    this.pageState = 'loading';
    this.error = null;
    this.agent2.recommend({ agent1SignalId: signal.id, userProfileId: profileId }).subscribe({
      next: card => {
        if (card.strategy === 'SKIP') {
          this.skipReason = card.rationale ?? 'Conditions not suitable for trading.';
          this.pageState = 'skip';
        } else {
          this.tradeCard = card;
          this.pageState = 'tradecard';
        }
      },
      error: err => {
        this.error = err?.error?.detail ?? 'Failed to generate recommendation.';
        this.pageState = 'ready';
      },
    });
  }

  confirm(): void {
    if (!this.tradeCard) return;
    this.agent2.confirm({ tradeId: this.tradeCard.tradeId, action: 'CONFIRM' }).subscribe({
      next: () => { this.pageState = 'active'; this.state.refreshTrades(); },
      error: err => { this.error = err?.error?.detail ?? 'Confirm failed.'; },
    });
  }

  reject(): void {
    if (!this.tradeCard) return;
    this.agent2.confirm({ tradeId: this.tradeCard.tradeId, action: 'REJECT' }).subscribe({
      next: () => this.reset(),
      error: () => this.reset(),
    });
  }

  reset(): void {
    this.tradeCard = null;
    this.error = null;
    this.skipReason = null;
    this.pageState = 'ready';
  }

  confColor(conf: string): string {
    if (conf === 'HIGH') return 'success';
    if (conf === 'MEDIUM') return 'warning';
    return 'medium';
  }

  strategyLabel(strategy: string): string {
    const map: Record<string, string> = {
      BULL_PUT_SPREAD: 'Bull Put Spread',
      BEAR_CALL_SPREAD: 'Bear Call Spread',
      BULL_CALL_SPREAD: 'Bull Call Spread',
      BEAR_PUT_SPREAD: 'Bear Put Spread',
      IRON_CONDOR: 'Iron Condor',
      SHORT_STRADDLE: 'Short Straddle',
      SHORT_STRANGLE: 'Short Strangle',
    };
    return map[strategy] ?? strategy;
  }
}
