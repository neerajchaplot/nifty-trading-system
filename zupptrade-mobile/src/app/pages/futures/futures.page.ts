import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonHeader, IonToolbar, IonTitle, IonContent, IonButtons, IonSegment, IonSegmentButton,
  IonLabel, IonCard, IonCardContent, IonButton, IonIcon, IonBadge, IonSpinner,
  IonRefresher, IonRefresherContent,
} from '@ionic/angular/standalone';
import { AppHeaderComponent } from '../../shared/components/app-header/app-header.component';
import { addIcons } from 'ionicons';
import { refreshOutline, checkmarkCircleOutline, flashOutline } from 'ionicons/icons';
import { Subscription, interval } from 'rxjs';
import { startWith, switchMap } from 'rxjs/operators';
import { FuturesService } from '../../core/services/futures.service';
import { UserStateService } from '../../core/services/user-state.service';
import { FuturesArmCard, FuturesPlanCard } from '../../core/models/futures.model';
import { FutureArmType } from '../../core/models/enums';
import { environment } from '../../../environments/environment';

type PlanState = 'awaiting' | 'loading' | 'card' | 'submitting' | 'armed' | 'error';

@Component({
  selector: 'app-futures',
  standalone: true,
  imports: [
    CommonModule,
    IonHeader, IonToolbar, IonTitle, IonContent, IonButtons, IonSegment, IonSegmentButton,
    IonLabel, IonCard, IonCardContent, IonButton, IonIcon, IonBadge, IonSpinner,
    IonRefresher, IonRefresherContent, AppHeaderComponent,
  ],
  template: `
    <app-header title="Futures"></app-header>

    <ion-content class="ion-padding">
      <ion-segment [value]="segment" (ionChange)="onSegment($any($event.detail.value))" style="margin-bottom:12px;">
        <ion-segment-button value="plan"><ion-label>Plan</ion-label></ion-segment-button>
        <ion-segment-button value="live"><ion-label>Live</ion-label></ion-segment-button>
      </ion-segment>

      <!-- ═══════════ PLAN ═══════════ -->
      <ng-container *ngIf="segment === 'plan'">

        <div *ngIf="planState === 'awaiting'" class="empty-state">
          <div class="empty-icon">🎯</div>
          <div class="empty-title">Intraday Futures Plan</div>
          <div class="empty-sub">Build today's Camarilla 4-arm plan and pick one to arm.</div>
          <ion-button (click)="getPlan()" style="margin-top:14px;">Get Plan</ion-button>
        </div>

        <div *ngIf="planState === 'loading'" class="empty-state">
          <ion-spinner name="crescent"></ion-spinner>
          <div class="empty-title" style="margin-top:16px;">Building plan…</div>
        </div>

        <div *ngIf="planState === 'error'" class="empty-state">
          <div class="empty-icon">⚠️</div>
          <div class="empty-title">Couldn't build plan</div>
          <div class="empty-sub">{{ error }}</div>
          <ion-button fill="outline" (click)="reset()" style="margin-top:14px;">
            <ion-icon name="refresh-outline" slot="start"></ion-icon> Try Again
          </ion-button>
        </div>

        <div *ngIf="planState === 'armed'" class="empty-state" style="padding-top:24px;">
          <div class="empty-icon">✅</div>
          <div class="empty-title">Plan Armed</div>
          <div class="empty-sub">Track it under the Live tab.</div>
          <ion-button fill="outline" (click)="reset()" style="margin-top:16px;">New Plan</ion-button>
        </div>

        <ng-container *ngIf="(planState === 'card' || planState === 'submitting') && card">
          <ion-card>
            <ion-card-content>
              <div style="display:flex; align-items:center; gap:8px; margin-bottom:6px;">
                <span style="font-size:16px; font-weight:800;">{{ card.planCode }}</span>
                <ion-badge [color]="biasColor(card.bias)">{{ card.bias | titlecase }}</ion-badge>
                <ion-badge color="medium" style="margin-left:auto;">{{ card.openZone | titlecase }} open</ion-badge>
              </div>
              <div style="font-size:12px; color:var(--zt-muted);">
                Confidence {{ card.confidenceLabel }} ({{ card.confidenceScore | number:'1.2-2' }}) ·
                Open {{ card.openPx | number:'1.0-0' }}
              </div>
              <div *ngIf="card.compressed" class="alert-t1" style="margin-top:8px;">
                ⚠ Compression detected — rotation arms vetoed
              </div>
            </ion-card-content>
          </ion-card>

          <ion-card>
            <ion-card-content>
              <div class="divider-label">Key Levels (Camarilla)</div>
              <div style="display:grid; grid-template-columns:repeat(3,1fr); gap:6px; font-size:12px;">
                <div><span style="color:var(--zt-muted);">H4</span> {{ card.keyLevels.h4 | number:'1.0-0' }}</div>
                <div><span style="color:var(--zt-muted);">H3</span> {{ card.keyLevels.h3 | number:'1.0-0' }}</div>
                <div><span style="color:var(--zt-muted);">Pivot</span> {{ card.keyLevels.pivot | number:'1.0-0' }}</div>
                <div><span style="color:var(--zt-muted);">L3</span> {{ card.keyLevels.l3 | number:'1.0-0' }}</div>
                <div><span style="color:var(--zt-muted);">L4</span> {{ card.keyLevels.l4 | number:'1.0-0' }}</div>
                <div><span style="color:var(--zt-muted);">Range</span> {{ card.keyLevels.range | number:'1.0-0' }}</div>
              </div>
            </ion-card-content>
          </ion-card>

          <div class="divider-label" style="margin:4px 4px 6px;">Pick an arm</div>
          <div *ngFor="let arm of card.arms"
               class="arm-card"
               [class.arm-selected]="selectedArm === arm.armType"
               [class.arm-blocked]="arm.status === 'BLOCKED'"
               (click)="selectArm(arm)">
            <div style="display:flex; align-items:center; justify-content:space-between;">
              <div style="display:flex; align-items:center; gap:6px;">
                <span style="font-size:14px; font-weight:700;">{{ arm.label }}</span>
                <ion-badge [color]="arm.direction === 'LONG' ? 'success' : 'danger'">{{ arm.direction }}</ion-badge>
                <ion-badge *ngIf="arm.status === 'RECOMMENDED'" color="primary">TOP</ion-badge>
              </div>
              <span style="font-size:13px; font-weight:700; color:var(--zt-blue);">R:R {{ arm.rrAfterCost | number:'1.1-1' }}</span>
            </div>
            <div style="display:flex; gap:12px; margin-top:6px; font-size:11px; color:var(--zt-sub);">
              <span>Entry {{ arm.entry | number:'1.0-0' }}</span>
              <span>Stop {{ arm.stop | number:'1.0-0' }}</span>
              <span>Target {{ arm.target | number:'1.0-0' }}</span>
              <span>{{ arm.lots }} lot{{ arm.lots === 1 ? '' : 's' }}</span>
            </div>
            <div *ngIf="arm.status === 'BLOCKED' && arm.blockedReason" style="font-size:11px; color:var(--zt-red); margin-top:4px;">
              {{ arm.blockedReason }}
            </div>
          </div>

          <div *ngIf="card.noTradeReason" class="empty-sub" style="margin-top:8px;">{{ card.noTradeReason }}</div>
          <div *ngIf="error" class="error-banner" style="margin-top:8px;">{{ error }}</div>

          <ion-button expand="block" color="success" style="margin-top:10px;"
                      [disabled]="!selectedArm || planState === 'submitting'"
                      (click)="arm()">
            <ion-icon name="flash-outline" slot="start"></ion-icon>
            {{ planState === 'submitting' ? 'Arming…' : 'Arm Selected Plan' }}
          </ion-button>
          <ion-button expand="block" fill="clear" (click)="reset()" style="margin-top:4px;">New Plan</ion-button>
        </ng-container>
      </ng-container>

      <!-- ═══════════ LIVE ═══════════ -->
      <ng-container *ngIf="segment === 'live'">
        <ion-refresher slot="fixed" (ionRefresh)="doRefresh($event)">
          <ion-refresher-content></ion-refresher-content>
        </ion-refresher>

        <div *ngIf="livePlans.length === 0" class="empty-state">
          <div class="empty-icon">📡</div>
          <div class="empty-title">No Active Plans</div>
          <div class="empty-sub">Armed and active futures plans appear here.</div>
        </div>

        <ion-card *ngFor="let p of livePlans" [style.opacity]="isActive(p) ? 1 : 0.6">
          <ion-card-content>
            <div style="display:flex; justify-content:space-between; align-items:center;">
              <span style="font-size:13px; font-weight:800;">{{ p.planCode }}</span>
              <ion-badge [color]="statusColor(p)">{{ p.status }}</ion-badge>
            </div>
            <ng-container *ngIf="chosenArm(p) as arm">
              <div style="display:flex; align-items:center; gap:6px; margin-top:8px;">
                <span style="font-size:14px; font-weight:700;">{{ arm.label }}</span>
                <ion-badge [color]="arm.direction === 'LONG' ? 'success' : 'danger'">{{ arm.direction }}</ion-badge>
              </div>
              <div style="display:flex; gap:12px; margin-top:6px; font-size:11px; color:var(--zt-sub);">
                <span>Entry {{ arm.entry | number:'1.0-0' }}</span>
                <span>Stop {{ arm.stop | number:'1.0-0' }}</span>
                <span>Target {{ arm.target | number:'1.0-0' }}</span>
              </div>
            </ng-container>
            <div *ngIf="isFailed(p)" class="alert-t3" style="margin-top:8px;">⚠ Entry handoff failed — check with broker</div>
          </ion-card-content>
        </ion-card>
      </ng-container>
    </ion-content>
  `,
  styles: [`
    .arm-card {
      background: var(--ion-card-background, #fff);
      border: 1px solid var(--zt-border);
      border-radius: 10px;
      padding: 12px;
      margin-bottom: 8px;
    }
    .arm-selected { border-color: var(--zt-blue); box-shadow: 0 0 0 1px var(--zt-blue); }
    .arm-blocked  { opacity: 0.55; }
  `],
})
export class FuturesPage implements OnInit, OnDestroy {
  private futures = inject(FuturesService);
  private userState = inject(UserStateService);

  segment: 'plan' | 'live' = 'plan';
  planState: PlanState = 'awaiting';
  card: FuturesPlanCard | null = null;
  selectedArm: FutureArmType | null = null;
  error: string | null = null;

  livePlans: FuturesPlanCard[] = [];
  private liveSub?: Subscription;

  constructor() {
    addIcons({ refreshOutline, checkmarkCircleOutline, flashOutline });
  }

  ngOnInit(): void {}
  ngOnDestroy(): void { this.liveSub?.unsubscribe(); }

  onSegment(value: 'plan' | 'live'): void {
    this.segment = value;
    if (value === 'live') this.startLivePolling();
    else this.liveSub?.unsubscribe();
  }

  // ── Plan ────────────────────────────────────────────────────────────────
  getPlan(): void {
    const userProfileId = this.userState.userProfileId;
    if (!userProfileId) { this.error = 'No user profile loaded.'; this.planState = 'error'; return; }
    this.planState = 'loading';
    this.error = null;
    this.futures.recommend({ userProfileId, runPhase: 900 }).subscribe({
      next: card => {
        this.card = card;
        this.selectedArm = card.primaryArm ?? null;
        this.planState = 'card';
      },
      error: err => { this.error = err?.error?.detail ?? 'Failed to build futures plan.'; this.planState = 'error'; },
    });
  }

  selectArm(arm: FuturesArmCard): void {
    if (arm.status === 'BLOCKED') return;
    this.selectedArm = arm.armType;
  }

  arm(): void {
    if (!this.card || !this.selectedArm) return;
    this.planState = 'submitting';
    this.error = null;
    this.futures.confirm({ planId: this.card.planId, action: 'CONFIRM', selectedArm: this.selectedArm }).subscribe({
      next: () => { this.planState = 'armed'; },
      error: err => { this.error = err?.error?.detail ?? 'Failed to arm the trade.'; this.planState = 'card'; },
    });
  }

  reset(): void {
    this.card = null;
    this.selectedArm = null;
    this.error = null;
    this.planState = 'awaiting';
  }

  biasColor(bias: string): string {
    if (bias === 'BULLISH') return 'success';
    if (bias === 'BEARISH') return 'danger';
    return 'medium';
  }

  // ── Live ────────────────────────────────────────────────────────────────
  private startLivePolling(): void {
    this.liveSub?.unsubscribe();
    this.liveSub = interval(environment.tradesPollIntervalMs).pipe(
      startWith(0),
      switchMap(() => this.futures.listActive()),
    ).subscribe({ next: plans => this.livePlans = plans, error: () => {} });
  }

  doRefresh(event: CustomEvent): void {
    this.futures.listActive().subscribe({
      next: plans => { this.livePlans = plans; (event.target as HTMLIonRefresherElement).complete(); },
      error: () => (event.target as HTMLIonRefresherElement).complete(),
    });
  }

  chosenArm(plan: FuturesPlanCard): FuturesArmCard | null {
    if (!plan.primaryArm) return plan.arms?.[0] ?? null;
    return plan.arms?.find(a => a.armType === plan.primaryArm) ?? null;
  }

  isActive(plan: FuturesPlanCard): boolean {
    return plan.status === 'CONFIRMED' || plan.status === 'FILLED';
  }

  isFailed(plan: FuturesPlanCard): boolean {
    return plan.status === 'EXECUTION_FAILED';
  }

  statusColor(plan: FuturesPlanCard): string {
    if (this.isFailed(plan)) return 'danger';
    if (this.isActive(plan)) return 'success';
    return 'medium';
  }
}
