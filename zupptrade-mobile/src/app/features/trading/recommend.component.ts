import { Component, EventEmitter, Output, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  IonCard, IonCardContent, IonButton, IonIcon, IonBadge, IonSpinner,
  IonSegment, IonSegmentButton, IonLabel, IonTextarea, IonAlert, IonToast, IonToggle,
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { checkmarkCircleOutline, closeCircleOutline, refreshOutline, pulseOutline, createOutline, walletOutline } from 'ionicons/icons';
import { of } from 'rxjs';
import { switchMap, catchError } from 'rxjs/operators';
import { DashboardStateService } from '../../core/services/dashboard-state.service';
import { Agent2Service } from '../../core/services/agent2.service';
import { Agent5Service } from '../../core/services/agent5.service';
import { UserStateService } from '../../core/services/user-state.service';
import {
  TradeCard, TradeLeg, LegOrderRequest, ExecuteTradeRequest, ExecuteTradeResponse, MarginCheckResult, OverrideThresholds,
} from '../../core/models/trade.model';
import { Agent1Signal } from '../../core/models/agent1-signal.model';

type FlowState = 'ready' | 'loading' | 'tradecard' | 'active' | 'skip';

/**
 * Trading → Recommend segment. One flow (matching the web Recommendation Engine):
 * market view → Generate signal → signal summary → Generate recommendation → trade card → Confirm.
 * Headerless; hosted inside TradingPage's ion-content.
 */
@Component({
  selector: 'app-recommend',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonCard, IonCardContent, IonButton, IonIcon, IonBadge, IonSpinner,
    IonSegment, IonSegmentButton, IonLabel, IonTextarea, IonAlert, IonToast, IonToggle,
  ],
  template: `
    <!-- READY: signal + generate flow -->
    <ng-container *ngIf="flow === 'ready'">
      <ng-container *ngIf="signal$ | async as signal">
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Current Signal</div>
            <div style="display:flex; align-items:center; gap:8px; margin-bottom:8px;">
              <span style="font-size:18px; font-weight:800;">{{ signal.bias | titlecase }} {{ signal.strength | titlecase }}</span>
              <ion-badge [color]="confColor(signal.confidence)">{{ signal.confidence }}</ion-badge>
            </div>
            <div style="font-size:12px; color:var(--zt-muted);">Score {{ signal.compositeScore | number:'1.3-3' }} · VIX {{ signal.vixLevel | number:'1.1-1' }}</div>
          </ion-card-content>
        </ion-card>
        <ion-button expand="block" (click)="generate(signal)">Generate Recommendation</ion-button>
      </ng-container>

      <!-- Signal generation in progress -->
      <div *ngIf="loading$ | async" class="gen-loading">
        <ion-spinner name="crescent"></ion-spinner>
        <div class="gen-msg">Fetching market data and scoring…</div>
        <div class="gen-sub">This can take 10–20 seconds.</div>
      </div>

      <ng-container *ngIf="(loading$ | async) !== true">
        <!-- Once a signal exists, the market-view box collapses behind a link -->
        <button *ngIf="(signal$ | async) && !showCommentary" class="refine-link" (click)="showCommentary = true">
          <ion-icon name="create-outline"></ion-icon> Refine &amp; regenerate signal
        </button>

        <ng-container *ngIf="!(signal$ | async) || showCommentary">
          <div *ngIf="!(signal$ | async)" class="empty-sub" style="text-align:left; margin:4px 0 8px;">No signal yet — add your view (optional) and generate.</div>
          <ion-card class="commentary-card">
            <ion-card-content>
              <div class="divider-label" style="display:flex; justify-content:space-between; align-items:center; margin-top:0;">
                <span>Your market view (optional)</span>
                <span [style.color]="commentaryTooLong ? 'var(--zt-red)' : 'var(--zt-muted)'">{{ wordCount }}/500</span>
              </div>
              <ion-textarea [(ngModel)]="commentary" [autoGrow]="true" [rows]="2"
                placeholder="e.g. RBI policy today; range-bound with mild downside."
                class="commentary-input"></ion-textarea>
            </ion-card-content>
          </ion-card>
          <ion-button expand="block" fill="outline" [disabled]="commentaryTooLong" (click)="generateSignal()">
            <ion-icon name="refresh-outline" slot="start"></ion-icon>
            {{ (signal$ | async) ? 'Regenerate Signal' : 'Generate Signal' }}
          </ion-button>
        </ng-container>
      </ng-container>

      <div *ngIf="error$ | async as err" class="error-banner" style="margin-top:8px;">{{ err }}</div>
    </ng-container>

    <!-- LOADING recommendation -->
    <div *ngIf="flow === 'loading'" class="empty-state">
      <ion-spinner name="crescent"></ion-spinner>
      <div class="empty-title" style="margin-top:16px;">Getting Recommendation…</div>
    </div>

    <!-- SKIP -->
    <div *ngIf="flow === 'skip'" class="empty-state">
      <div class="empty-icon">⏭️</div>
      <div class="empty-title">No Trade Today</div>
      <div class="empty-sub">{{ skipReason }}</div>
      <ion-button fill="outline" (click)="reset()" style="margin-top:16px;">
        <ion-icon name="refresh-outline" slot="start"></ion-icon> Try Again
      </ion-button>
    </div>

    <!-- TRADECARD -->
    <ng-container *ngIf="flow === 'tradecard' && tradeCard">
      <div *ngIf="tradeCard.testingModeActive" style="background:#FEF3C7; border:1px solid #F59E0B; border-radius:8px; padding:8px 12px; margin-bottom:8px; font-size:12px; color:#92400E;">
        ⚠️ TESTING MODE — hard gates bypassed.
      </div>
      <div *ngIf="tradeCard.skipDecision" style="background:#FEE2E2; border:1px solid #F87171; border-radius:8px; padding:8px 12px; margin-bottom:8px; font-size:12px; color:#991B1B;">
        Skip Decision Overridden — original: {{ tradeCard.skipReason }}. Fallback applied.
      </div>

      <ion-card>
        <ion-card-content>
          <div style="display:flex; align-items:center; gap:8px; margin-bottom:8px;">
            <span style="font-size:16px; font-weight:800;">{{ strategyLabel(tradeCard.strategy) }}</span>
            <ion-badge [color]="tradeCard.spreadDirection === 'CREDIT' ? 'success' : 'primary'">{{ tradeCard.spreadDirection }}</ion-badge>
          </div>
          <div style="font-size:12px; color:var(--zt-muted);">Expiry {{ tradeCard.expiryDate }} · DTE {{ tradeCard.dte }} days</div>
        </ion-card-content>
      </ion-card>

      <ion-segment [value]="cardTab" (ionChange)="cardTab = $any($event.detail.value)" style="margin-bottom:12px;">
        <ion-segment-button value="summary"><ion-label>Summary</ion-label></ion-segment-button>
        <ion-segment-button value="legs"><ion-label>Legs</ion-label></ion-segment-button>
        <ion-segment-button value="checks"><ion-label>Checks</ion-label></ion-segment-button>
      </ion-segment>

      <ng-container *ngIf="cardTab === 'summary'">
        <ion-card>
          <ion-card-content>
            <div class="divider-label">P&L Summary</div>
            <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
              <div>
                <div class="cell-k">Net Premium</div>
                <div style="font-size:16px; font-weight:800; color:var(--zt-green);">₹{{ tradeCard.netPremiumPerUnit | number:'1.2-2' }}</div>
              </div>
              <div>
                <div class="cell-k">Lots</div>
                <div style="display:flex; align-items:center; gap:6px; margin-top:2px;">
                  <button class="lot-btn" [disabled]="lots <= 1" (click)="decLots()">−</button>
                  <span style="font-size:16px; font-weight:800; min-width:22px; text-align:center;">{{ lots }}</span>
                  <button class="lot-btn" [disabled]="lots >= tradeCard.lots" (click)="incLots()">+</button>
                  <span style="font-size:11px; color:var(--zt-muted);">× {{ tradeCard.lotSize }}</span>
                </div>
              </div>
              <div>
                <div class="cell-k">Max Profit</div>
                <div style="font-size:15px; font-weight:700; color:var(--zt-green);">₹{{ scaledMaxProfit | number:'1.0-0' }}</div>
              </div>
              <div>
                <div class="cell-k">Expected Loss</div>
                <div style="font-size:15px; font-weight:700; color:var(--zt-red);">₹{{ scaledExpLoss | number:'1.0-0' }}</div>
              </div>
              <div>
                <div class="cell-k">PoP</div>
                <div style="font-size:15px; font-weight:700;">{{ tradeCard.pop | number:'1.1-1' }}%</div>
              </div>
              <div>
                <div class="cell-k">RoC</div>
                <div style="font-size:15px; font-weight:700; color:var(--zt-blue);">{{ tradeCard.roc | number:'1.2-2' }}%</div>
              </div>
            </div>
            <div *ngIf="lotsOverridden" style="font-size:11px; color:var(--zt-amber); margin-top:8px;">
              Sizing to {{ lots }} of {{ tradeCard.lots }} recommended lots (P&L scaled).
            </div>
          </ion-card-content>
        </ion-card>

        <!-- Monitoring-level override (T1/T2/T3) — mirrors web overrideThresholds -->
        <ion-card>
          <ion-card-content>
            <div style="display:flex; justify-content:space-between; align-items:center;">
              <span class="divider-label" style="margin:0;">Monitoring Levels</span>
              <ion-toggle *ngIf="!isIronCondor" [checked]="overrideThresholdsOn" (ionChange)="toggleThresholdOverride($any($event.detail.checked))"></ion-toggle>
            </div>

            <!-- Iron Condor: bilateral exit ladder is algorithm-locked (same as web) -->
            <div *ngIf="isIronCondor" class="empty-sub" style="text-align:left; margin-top:6px;">
              Iron Condor — the exit ladder is bilateral (put + call side) and algorithm-locked. See the levels under Checks.
            </div>

            <ng-container *ngIf="!isIronCondor">
              <div *ngIf="!overrideThresholdsOn" class="empty-sub" style="text-align:left; margin-top:6px;">
                Using algorithm levels. Turn on to set your own T1/T2/T3 Nifty exit levels.
              </div>
              <div *ngIf="overrideThresholdsOn" class="thr-grid">
                <div>
                  <div class="cell-k">T1 Watch</div>
                  <input type="number" inputmode="numeric" class="thr-input" [(ngModel)]="overrideT1">
                </div>
                <div>
                  <div class="cell-k">T2 Readjust</div>
                  <input type="number" inputmode="numeric" class="thr-input" [(ngModel)]="overrideT2">
                </div>
                <div>
                  <div class="cell-k">T3 Exit</div>
                  <input type="number" inputmode="numeric" class="thr-input" [(ngModel)]="overrideT3">
                </div>
              </div>
              <div *ngIf="thresholdsOverridden" style="font-size:11px; color:var(--zt-amber); margin-top:8px;">
                Custom monitoring levels will be applied on Confirm.
              </div>
            </ng-container>
          </ion-card-content>
        </ion-card>

        <div *ngIf="error" class="error-banner">{{ error }}</div>

        <!-- Margin check (Agent 5) — optional pre-flight before Confirm -->
        <ion-button expand="block" fill="outline" size="small" [disabled]="marginLoading" (click)="checkMargin()" style="margin-top:8px;">
          <ion-icon name="wallet-outline" slot="start"></ion-icon>
          {{ marginLoading ? 'Checking margin…' : 'Check Margin' }}
        </ion-button>
        <div *ngIf="marginResult as m" class="margin-box" [class.margin-bad]="!m.sufficient">
          <div class="margin-row"><span>Required</span><b>₹{{ m.requiredMargin | number:'1.0-0' }}</b></div>
          <div class="margin-row"><span>Available</span><b>₹{{ m.availableMargin | number:'1.0-0' }}</b></div>
          <div class="margin-row" *ngIf="!m.sufficient && m.shortfall != null"><span>Shortfall</span><b style="color:var(--zt-red);">₹{{ m.shortfall | number:'1.0-0' }}</b></div>
          <div class="margin-flag" [style.color]="m.sufficient ? 'var(--zt-green)' : 'var(--zt-red)'">
            {{ m.sufficient ? '✓ Sufficient margin' : '✗ Insufficient margin' }}
          </div>
        </div>

        <div style="display:flex; gap:10px; margin-top:8px;">
          <ion-button color="danger" fill="outline" expand="block" style="flex:1;" (click)="reject()">
            <ion-icon name="close-circle-outline" slot="start"></ion-icon> Reject
          </ion-button>
          <ion-button color="success" expand="block" style="flex:1;" (click)="confirmOpen = true">
            <ion-icon name="checkmark-circle-outline" slot="start"></ion-icon> Confirm
          </ion-button>
        </div>

        <ion-alert
          [isOpen]="confirmOpen"
          header="Place this trade?"
          [message]="confirmMessage"
          [buttons]="confirmButtons"
          (didDismiss)="confirmOpen = false"></ion-alert>
      </ng-container>

      <ng-container *ngIf="cardTab === 'legs'">
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Legs</div>
            <div style="display:flex; gap:8px;">
              <div class="leg-card leg-sell"><div class="leg-action">SELL</div><div class="leg-strike">{{ tradeCard.shortLeg.strike }}</div><div class="leg-meta">{{ tradeCard.shortLeg.optionType }} · ₹{{ tradeCard.shortLeg.ltp | number:'1.2-2' }}</div></div>
              <div class="leg-card leg-buy"><div class="leg-action">BUY</div><div class="leg-strike">{{ tradeCard.longLeg.strike }}</div><div class="leg-meta">{{ tradeCard.longLeg.optionType }} · ₹{{ tradeCard.longLeg.ltp | number:'1.2-2' }}</div></div>
            </div>
            <div *ngIf="tradeCard.shortLeg2" style="display:flex; gap:8px; margin-top:8px;">
              <div class="leg-card leg-sell"><div class="leg-action">SELL</div><div class="leg-strike">{{ tradeCard.shortLeg2!.strike }}</div><div class="leg-meta">{{ tradeCard.shortLeg2!.optionType }} · ₹{{ tradeCard.shortLeg2!.ltp | number:'1.2-2' }}</div></div>
              <div class="leg-card leg-buy"><div class="leg-action">BUY</div><div class="leg-strike">{{ tradeCard.longLeg2!.strike }}</div><div class="leg-meta">{{ tradeCard.longLeg2!.optionType }} · ₹{{ tradeCard.longLeg2!.ltp | number:'1.2-2' }}</div></div>
            </div>
          </ion-card-content>
        </ion-card>
      </ng-container>

      <ng-container *ngIf="cardTab === 'checks'">
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Gate Checks</div>
            <div *ngFor="let g of tradeCard.gateResults" style="display:flex; justify-content:space-between; align-items:center; padding:5px 0; border-bottom:1px solid var(--zt-border);">
              <span style="font-size:12px; color:var(--zt-sub);">{{ g.gate }}</span>
              <ion-badge [color]="g.passed ? 'success' : 'danger'">{{ g.passed ? 'PASS' : 'FAIL' }}</ion-badge>
            </div>
          </ion-card-content>
        </ion-card>
        <ion-card>
          <ion-card-content>
            <div class="divider-label">Monitoring Levels</div>

            <!-- 2-leg spread: a single Watch/Readjust/Exit ladder -->
            <div *ngIf="!isIronCondor" style="display:flex; flex-direction:column; gap:6px; font-size:12px;">
              <div class="lvl-row"><span class="level-t1">T1 Watch</span><span>{{ tradeCard.thresholds.t1WatchNiftyLevel | number:'1.0-0' }}</span></div>
              <div class="lvl-row"><span class="level-t2">T2 Readjust</span><span>{{ tradeCard.thresholds.t2ReadjustNiftyLevel | number:'1.0-0' }}</span></div>
              <div class="lvl-row"><span class="level-t3">T3 Exit</span><span>{{ tradeCard.thresholds.t3ExitNiftyLevel | number:'1.0-0' }}</span></div>
            </div>

            <!-- Iron Condor: two ladders — put side (Nifty falls) + call side (Nifty rises) -->
            <div *ngIf="isIronCondor" class="ic-grid">
              <div class="ic-col">
                <div class="ic-head">Put side <span class="ic-sub">· Nifty falls</span></div>
                <div class="lvl-row"><span class="level-t1">T1 Watch</span><span>{{ tradeCard.thresholds.t1WatchNiftyDown | number:'1.0-0' }}</span></div>
                <div class="lvl-row"><span class="level-t2">T2 Readjust</span><span>{{ tradeCard.thresholds.t2ReadjustNiftyDown | number:'1.0-0' }}</span></div>
                <div class="lvl-row"><span class="level-t3">T3 Exit</span><span>{{ tradeCard.thresholds.t3ExitNiftyDown | number:'1.0-0' }}</span></div>
              </div>
              <div class="ic-col">
                <div class="ic-head">Call side <span class="ic-sub">· Nifty rises</span></div>
                <div class="lvl-row"><span class="level-t1">T1 Watch</span><span>{{ tradeCard.thresholds.t1WatchNiftyUp | number:'1.0-0' }}</span></div>
                <div class="lvl-row"><span class="level-t2">T2 Readjust</span><span>{{ tradeCard.thresholds.t2ReadjustNiftyUp | number:'1.0-0' }}</span></div>
                <div class="lvl-row"><span class="level-t3">T3 Exit</span><span>{{ tradeCard.thresholds.t3ExitNiftyUp | number:'1.0-0' }}</span></div>
              </div>
            </div>
          </ion-card-content>
        </ion-card>
      </ng-container>

      <ion-button expand="block" fill="clear" (click)="reset()" style="margin-top:4px;">New Recommendation</ion-button>
    </ng-container>

    <!-- ACTIVE / execution outcome -->
    <div *ngIf="flow === 'active'" class="empty-state" style="padding-top:24px;">
      <ng-container *ngIf="!executionResponse || executionResponse.executionStatus === 'ACTIVE'">
        <div class="empty-icon">✅</div>
        <div class="empty-title">Trade Confirmed</div>
        <div class="empty-sub">Your position is live — track it under Live.</div>
        <div *ngIf="executionResponse?.slippageAlert" class="slippage-note">
          ⚠️ {{ executionResponse?.slippageMessage || 'Fill slippage exceeded the 10% threshold.' }}
        </div>
        <ion-button (click)="viewLive.emit()" style="margin-top:16px;">
          <ion-icon name="pulse-outline" slot="start"></ion-icon> View Live Trades
        </ion-button>
      </ng-container>
      <ng-container *ngIf="executionResponse && executionResponse.executionStatus !== 'ACTIVE'">
        <div class="empty-icon">⚠️</div>
        <div class="empty-title">Execution {{ executionResponse.executionStatus | titlecase }}</div>
        <div class="empty-sub">{{ executionResponse.rejectionReason || 'The order could not be completed. No position was opened.' }}</div>
      </ng-container>
      <ion-button fill="clear" (click)="reset()" style="margin-top:4px;">New Recommendation</ion-button>
    </div>

    <ion-toast [isOpen]="toastOpen" [message]="toastMsg" [duration]="2000" position="bottom" (didDismiss)="toastOpen = false"></ion-toast>
  `,
  styles: [`
    .commentary-card { margin-top: 8px; }
    .commentary-input { border: 1px solid var(--zt-border); border-radius: 8px; --padding-start: 8px; --padding-end: 8px; margin-top: 4px; font-size: 13px; }
    .cell-k { font-size:10px; color:var(--zt-muted); text-transform:uppercase; }
    .lot-btn { width:40px; height:40px; border-radius:8px; border:1px solid var(--zt-border); background:#fff; font-size:20px; font-weight:700; color:var(--zt-blue); line-height:1; cursor:pointer; font-family:inherit; }
    .lot-btn:disabled { opacity:0.4; }
    .gen-loading { display:flex; flex-direction:column; align-items:center; gap:4px; padding:28px 16px; text-align:center; }
    .gen-msg { font-size:14px; font-weight:600; color:var(--zt-text); margin-top:8px; }
    .gen-sub { font-size:12px; color:var(--zt-muted); }
    .refine-link { display:inline-flex; align-items:center; gap:6px; background:none; border:none; color:var(--zt-blue); font-size:13px; font-weight:600; font-family:inherit; padding:10px 0; cursor:pointer; }
    .margin-box { border:1px solid var(--zt-border); border-radius:8px; padding:8px 12px; margin-top:8px; }
    .margin-box.margin-bad { border-color:var(--zt-red); background:rgba(220,38,38,0.05); }
    .margin-row { display:flex; justify-content:space-between; font-size:12px; padding:2px 0; color:var(--zt-sub); }
    .margin-flag { font-size:12px; font-weight:700; margin-top:4px; text-align:center; }
    .slippage-note { font-size:12px; color:var(--zt-amber); background:rgba(217,119,6,0.12); border:1px solid rgba(217,119,6,0.3); border-radius:8px; padding:8px 12px; margin-top:10px; max-width:320px; }
    .thr-grid { display:grid; grid-template-columns:1fr 1fr 1fr; gap:8px; margin-top:8px; }
    .thr-input { width:100%; border:1px solid var(--zt-border); border-radius:8px; padding:8px; font-size:14px; font-weight:700; font-family:inherit; color:var(--zt-text); background:#fff; box-sizing:border-box; }
    .lvl-row { display:flex; justify-content:space-between; }
    .ic-grid { display:flex; gap:16px; }
    .ic-col { flex:1 1 0; display:flex; flex-direction:column; gap:6px; font-size:12px; }
    .ic-head { font-weight:700; font-size:12px; margin-bottom:2px; }
    .ic-sub { font-weight:400; color:var(--zt-muted); }
  `],
})
export class RecommendComponent implements OnInit {
  @Output() viewLive = new EventEmitter<void>();

  private state = inject(DashboardStateService);
  private agent2 = inject(Agent2Service);
  private agent5 = inject(Agent5Service);
  private userState = inject(UserStateService);

  signal$ = this.state.signal;
  loading$ = this.state.signalLoading;
  error$ = this.state.signalError;

  commentary = '';
  get wordCount(): number { const t = this.commentary.trim(); return t ? t.split(/\s+/).length : 0; }
  get commentaryTooLong(): boolean { return this.wordCount > 500; }

  flow: FlowState = 'ready';
  showCommentary = false;
  toastOpen = false;
  toastMsg = '';
  tradeCard: TradeCard | null = null;
  cardTab: 'summary' | 'legs' | 'checks' = 'summary';
  lots = 0;
  error: string | null = null;
  skipReason: string | null = null;

  // Agent 5 — execution
  marginResult: MarginCheckResult | null = null;
  marginLoading = false;
  executionResponse: ExecuteTradeResponse | null = null;

  // Threshold override (T1/T2/T3) — mirrors web overrideThresholds
  overrideThresholdsOn = false;
  overrideT1: number | null = null;
  overrideT2: number | null = null;
  overrideT3: number | null = null;

  ngOnInit(): void {
    addIcons({ checkmarkCircleOutline, closeCircleOutline, refreshOutline, pulseOutline, createOutline, walletOutline });
  }

  generateSignal(): void { this.state.generateSignal(this.commentary); }

  generate(signal: Agent1Signal): void {
    const profileId = this.userState.userProfileId;
    if (!profileId) { this.error = 'No user profile loaded.'; return; }
    this.flow = 'loading';
    this.error = null;
    this.agent2.recommend({ agent1SignalId: signal.id, userProfileId: profileId }).subscribe({
      next: card => {
        if (!card.testingModeActive && card.strategy === 'SKIP') {
          this.skipReason = card.rationale ?? 'Conditions not suitable for trading.';
          this.flow = 'skip';
        } else {
          this.tradeCard = card;
          this.cardTab = 'summary';
          this.lots = card.lots;
          this.flow = 'tradecard';
        }
      },
      error: err => { this.error = err?.error?.detail ?? 'Failed to generate recommendation.'; this.flow = 'ready'; },
    });
  }

  // Confirmation dialog before committing the trade.
  confirmOpen = false;
  readonly confirmButtons = [
    { text: 'Cancel', role: 'cancel' },
    { text: 'Confirm', role: 'confirm', handler: () => this.doConfirm() },
  ];
  get confirmMessage(): string {
    if (!this.tradeCard) return '';
    return `${this.strategyLabel(this.tradeCard.strategy)} · ${this.lots} lot${this.lots === 1 ? '' : 's'} · max loss ₹${this.scaledExpLoss.toLocaleString('en-IN')}.`;
  }

  // Confirm (Agent 2) → execute (Agent 5), mirroring the web Recommendation Engine.
  doConfirm(): void {
    if (!this.tradeCard) return;
    this.error = null;
    this.agent2.confirm({
      tradeId: this.tradeCard.tradeId,
      action: 'CONFIRM',
      overrideLots: this.lotsOverridden ? this.lots : null,
      overrideThresholds: this.buildOverrideThresholds(),
    }).pipe(
      switchMap(card => {
        this.tradeCard = card;                                   // confirmed card carries final lots/legs
        return this.agent5.execute(this.buildExecuteRequest(card));
      }),
      catchError(err => {
        this.error = err?.error?.detail ?? 'Confirm or execution failed.';
        return of(null);
      }),
    ).subscribe(exec => {
      if (exec) {
        this.executionResponse = exec;
        this.flow = 'active';
        this.state.refreshTrades();
      }
    });
  }

  // Optional pre-flight margin check before Confirm.
  checkMargin(): void {
    if (!this.tradeCard) return;
    this.marginResult = null;
    this.marginLoading = true;
    this.error = null;
    this.agent5.checkMargin({
      tradeId: this.tradeCard.tradeId,
      overrideLots: this.lotsOverridden ? this.lots : null,
    }).pipe(
      catchError(err => {
        this.error = err?.error?.detail ?? 'Margin check failed. Please try again.';
        return of(null);
      }),
    ).subscribe(result => {
      this.marginLoading = false;
      if (result) this.marginResult = result;
    });
  }

  private buildExecuteRequest(card: TradeCard): ExecuteTradeRequest {
    const qty = card.lots * card.lotSize;
    const leg = (l: TradeLeg): LegOrderRequest => ({
      instrumentKey: l.instrumentKey!,
      optionType:    l.optionType,
      strike:        l.strike,
      action:        l.action,
      limitPrice:    l.ltp,
      quantity:      qty,
    });
    const legs: LegOrderRequest[] = [leg(card.shortLeg), leg(card.longLeg)];
    if (card.shortLeg2 && card.longLeg2) {
      legs.push(leg(card.shortLeg2), leg(card.longLeg2));
    }
    return { tradeId: card.tradeId, legs };
  }

  // ── Threshold override (T1/T2/T3) ────────────────────────────────────────
  toggleThresholdOverride(on: boolean): void {
    this.overrideThresholdsOn = on;
    if (on && this.tradeCard) {
      // Pre-fill with the algorithm's levels so the user adjusts from there.
      this.overrideT1 = this.tradeCard.thresholds.t1WatchNiftyLevel;
      this.overrideT2 = this.tradeCard.thresholds.t2ReadjustNiftyLevel;
      this.overrideT3 = this.tradeCard.thresholds.t3ExitNiftyLevel;
    } else if (!on) {
      this.overrideT1 = this.overrideT2 = this.overrideT3 = null;
    }
  }

  get thresholdsOverridden(): boolean {
    if (!this.overrideThresholdsOn || !this.tradeCard) return false;
    const t = this.tradeCard.thresholds;
    return this.overrideT1 !== t.t1WatchNiftyLevel
        || this.overrideT2 !== t.t2ReadjustNiftyLevel
        || this.overrideT3 !== t.t3ExitNiftyLevel;
  }

  private buildOverrideThresholds(): OverrideThresholds | null {
    if (!this.overrideThresholdsOn) return null;
    const hasAny = this.overrideT1 !== null || this.overrideT2 !== null || this.overrideT3 !== null;
    if (!hasAny) return null;
    return {
      t1WatchNiftyLevel:    this.overrideT1,
      t2ReadjustNiftyLevel: this.overrideT2,
      t3ExitNiftyLevel:     this.overrideT3,
    };
  }

  reject(): void {
    if (!this.tradeCard) return;
    this.agent2.confirm({ tradeId: this.tradeCard.tradeId, action: 'REJECT' }).subscribe({
      next: () => this.dismissed(),
      error: () => this.dismissed(),
    });
  }

  private dismissed(): void {
    this.reset();
    this.toastMsg = 'Recommendation dismissed';
    this.toastOpen = true;
  }

  reset(): void {
    this.tradeCard = null;
    this.error = null;
    this.skipReason = null;
    this.cardTab = 'summary';
    this.lots = 0;
    this.marginResult = null;
    this.marginLoading = false;
    this.executionResponse = null;
    this.overrideThresholdsOn = false;
    this.overrideT1 = this.overrideT2 = this.overrideT3 = null;
    this.flow = 'ready';
  }

  get isIronCondor(): boolean { return this.tradeCard?.strategy === 'IRON_CONDOR'; }
  get lotsOverridden(): boolean { return !!this.tradeCard && this.lots !== this.tradeCard.lots; }
  get scaledMaxProfit(): number {
    if (!this.tradeCard || !this.tradeCard.lots) return 0;
    return Math.round(this.tradeCard.maxProfitTotal / this.tradeCard.lots * this.lots);
  }
  get scaledExpLoss(): number {
    if (!this.tradeCard || !this.tradeCard.lots) return 0;
    return Math.round(this.tradeCard.realExpectedLossTotal / this.tradeCard.lots * this.lots);
  }
  incLots(): void { if (this.tradeCard && this.lots < this.tradeCard.lots) this.lots++; }
  decLots(): void { if (this.lots > 1) this.lots--; }

  confColor(conf: string): string {
    if (conf === 'HIGH') return 'success';
    if (conf === 'MEDIUM') return 'warning';
    return 'medium';
  }
  strategyLabel(strategy: string): string {
    const map: Record<string, string> = {
      BULL_PUT_SPREAD: 'Bull Put Spread', BEAR_CALL_SPREAD: 'Bear Call Spread',
      BULL_CALL_SPREAD: 'Bull Call Spread', BEAR_PUT_SPREAD: 'Bear Put Spread',
      IRON_CONDOR: 'Iron Condor', SHORT_STRADDLE: 'Short Straddle', SHORT_STRANGLE: 'Short Strangle',
    };
    return map[strategy] ?? strategy;
  }
}
