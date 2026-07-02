import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Agent1Signal } from '../../core/models/agent1-signal.model';
import {
  CalculateOverrideResult,
  ExecuteTradeRequest,
  ExecuteTradeResponse,
  LegOrderRequest,
  MarginCheckResult,
  OverrideParams,
  OverrideThresholds,
  TradeCard,
} from '../../core/models/trade.model';
import { Agent1Service } from '../../core/services/agent1.service';
import { Agent2Service } from '../../core/services/agent2.service';
import { Agent5Service } from '../../core/services/agent5.service';
import { UserStateService } from '../../core/services/user-state.service';
import { BiasPillComponent } from '../../shared/components/bias-pill/bias-pill.component';
import { ConfidencePillComponent } from '../../shared/components/confidence-pill/confidence-pill.component';
import { GateBadgeComponent } from '../../shared/components/gate-badge/gate-badge.component';
import { MetricBoxComponent } from '../../shared/components/metric-box/metric-box.component';
import { catchError, of, switchMap } from 'rxjs';
import { Subject, debounceTime, takeUntil, distinctUntilChanged } from 'rxjs';

type PanelState = 'awaiting-view' | 'loading-signal' | 'signal-ready' | 'loading' | 'tradecard' | 'rejected' | 'active';

// PoP floor used when re-running recommend in override mode for rejected trades
const OVERRIDE_RELAXED_POP_FLOOR = 65;
// Hard block — never allow confirm if PoP is below this
const HARD_POP_FLOOR = 50;

@Component({
  selector: 'app-recommendation',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatProgressSpinnerModule,
    BiasPillComponent, ConfidencePillComponent, GateBadgeComponent, MetricBoxComponent,
  ],
  templateUrl: './recommendation.component.html',
  styleUrls: ['./recommendation.component.scss'],
})
export class RecommendationComponent implements OnInit, OnChanges, OnDestroy {
  @Input() signal: Agent1Signal | null = null;

  // ── Core state ───────────────────────────────────────────────────────────
  state: PanelState = 'awaiting-view';
  tradeCard: TradeCard | null = null;
  freshSignal: Agent1Signal | null = null;   // signal generated from the user's view
  errorMessage: string | null = null;
  loadingMessage = 'Generating signal…';

  // ── Commentary / User View (Tier 4 input — captured before signal generation) ──
  commentary = '';

  // ── Override panel — passed trade state ─────────────────────────────────
  overrideMode = false;
  overrideLots: number | null = null;         // null = use algorithm value
  overrideT1: number | null = null;
  overrideT2: number | null = null;
  overrideT3: number | null = null;
  marginResult: MarginCheckResult | null = null;
  marginLoading = false;

  // ── Override panel — rejected trade state (old relaxed re-run, kept for compatibility) ────
  overrideRecommendation: TradeCard | null = null;
  overrideLoading = false;

  // ── Execution result ──────────────────────────────────────────────────────
  executionResponse: ExecuteTradeResponse | null = null;

  // ── Manual override builder ────────────────────────────────────────────────
  builderMode = false;
  obPeShortStrike: number | null = null;
  obPeLongStrike: number | null = null;
  obCeShortStrike: number | null = null;   // null for 2-leg spreads
  obCeLongStrike: number | null = null;
  obLots: number | null = null;
  overrideMetrics: CalculateOverrideResult | null = null;
  overrideMetricsLoading = false;

  private readonly overrideCalcTrigger$ = new Subject<void>();
  private readonly destroy$ = new Subject<void>();

  // ── Computed properties ───────────────────────────────────────────────────

  // The signal to show in the header — fresh if generated, otherwise polled
  get effectiveSignal(): Agent1Signal | null {
    return this.freshSignal ?? this.signal;
  }

  get wordCount(): number {
    const text = this.commentary.trim();
    return text ? text.split(/\s+/).length : 0;
  }

  get commentaryTooLong(): boolean {
    return this.wordCount > 500;
  }

  // Effective lots for margin check: user override, or trade card value
  get effectiveLots(): number {
    return this.overrideLots ?? (this.tradeCard?.lots ?? 0);
  }

  // PoP of whichever card is the current result in override mode
  get overridePop(): number | null {
    return this.overrideRecommendation?.pop ?? null;
  }

  // Hard block: PoP must be ≥ 50% before any confirm
  get isPopBlocked(): boolean {
    const pop = this.state === 'rejected' ? this.overridePop : this.tradeCard?.pop ?? null;
    return pop !== null && pop < HARD_POP_FLOOR;
  }

  // T3 must not go below the short strike (PE short) or above it (CE short)
  get t3BreachesShortStrike(): boolean {
    if (!this.tradeCard || this.overrideT3 === null) return false;
    const shortStrike = this.tradeCard.shortLeg.strike;
    const isPeShort   = this.tradeCard.shortLeg.optionType === 'PE';
    return isPeShort
      ? this.overrideT3 < shortStrike
      : this.overrideT3 > shortStrike;
  }

  // Ordering validation: PE short → T1 > T2 > T3; CE short → T1 < T2 < T3
  get thresholdOrderInvalid(): boolean {
    if (!this.tradeCard) return false;
    const t1 = this.overrideT1 ?? this.tradeCard.thresholds.t1WatchNiftyLevel;
    const t2 = this.overrideT2 ?? this.tradeCard.thresholds.t2ReadjustNiftyLevel;
    const t3 = this.overrideT3 ?? this.tradeCard.thresholds.t3ExitNiftyLevel;
    if (t1 == null || t2 == null || t3 == null) return false;
    const isPeShort = this.tradeCard.shortLeg.optionType === 'PE';
    return isPeShort ? !(t1 > t2 && t2 > t3) : !(t1 < t2 && t2 < t3);
  }

  get overrideThresholdError(): string | null {
    if (this.t3BreachesShortStrike) {
      const shortStrike = this.tradeCard?.shortLeg.strike;
      const isPeShort   = this.tradeCard?.shortLeg.optionType === 'PE';
      return isPeShort
        ? `T3 cannot go below the short strike (${shortStrike}). PoP would drop below 50%.`
        : `T3 cannot go above the short strike (${shortStrike}). PoP would drop below 50%.`;
    }
    if (this.thresholdOrderInvalid) {
      const isPeShort = this.tradeCard?.shortLeg.optionType === 'PE';
      return isPeShort
        ? 'T1 must be highest, then T2, then T3 (levels decrease as Nifty falls).'
        : 'T1 must be lowest, then T2, then T3 (levels increase as Nifty rises).';
    }
    return null;
  }

  // Whether the current override configuration can proceed to confirm
  get canConfirmWithOverride(): boolean {
    if (this.isPopBlocked) return false;
    if (this.t3BreachesShortStrike || this.thresholdOrderInvalid) return false;
    if (!this.marginResult) return false;
    return this.marginResult.sufficient;
  }

  constructor(
    private agent1: Agent1Service,
    private agent2: Agent2Service,
    private agent5: Agent5Service,
    private userState: UserStateService,
  ) {}

  ngOnInit(): void {
    // Wire debounced override recalculation — fires 500ms after any field change
    this.overrideCalcTrigger$.pipe(
      debounceTime(500),
      takeUntil(this.destroy$),
    ).subscribe(() => this.runOverrideCalc());
  }

  ngOnChanges(_changes: SimpleChanges): void {
    // @Input signal is used as fallback in effectiveSignal — no action needed on change
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Step 1: Generate Signal (with user's view as Tier 4 input) ─────────

  generateSignal(): void {
    if (this.commentaryTooLong) return;
    this.state = 'loading-signal';
    this.errorMessage = null;
    this.freshSignal = null;
    this.loadingMessage = 'Fetching market data and generating signal…';

    const trimmedCommentary = this.commentary.trim();
    this.agent1.score({
      commentary: trimmedCommentary || undefined,
      marketaux_fetch: true,
    }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Failed to generate signal. Please try again.';
        this.state = 'awaiting-view';
        return of(null);
      })
    ).subscribe(signal => {
      if (!signal) return;
      this.freshSignal = signal;
      this.state = 'signal-ready';
    });
  }

  // ── Step 2: Generate Recommendation (using the fresh signal) ────────────

  generateRecommendation(): void {
    if (!this.freshSignal) return;
    this.state = 'loading';
    this.errorMessage = null;
    this.loadingMessage = 'Running 5-layer algorithm…';
    this.resetOverrideState();

    this.agent2.recommend({
      agent1SignalId: this.freshSignal.id,
      userProfileId: this.userState.userProfileId!,
    }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Failed to generate recommendation. Please try again.';
        this.state = 'signal-ready';
        return of(null);
      })
    ).subscribe(card => {
      if (!card) return;
      this.tradeCard = card;
      this.state = card.status === 'REJECTED' ? 'rejected' : 'tradecard';
    });
  }

  // ── Confirm / Reject ──────────────────────────────────────────────────────

  confirmTrade(): void {
    if (!this.tradeCard) return;

    const overrideThresholds = this.buildOverrideThresholds();

    this.agent2.confirm({
      tradeId: this.tradeCard.tradeId,
      action: 'CONFIRM',
      overrideLots: this.overrideLots,
      overrideThresholds,
    }).pipe(
      switchMap(card => {
        this.tradeCard = card;
        return this.agent5.execute(this.buildExecuteRequest(card));
      }),
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Confirm or execution failed.';
        return of(null);
      })
    ).subscribe(execResponse => {
      if (execResponse) {
        this.executionResponse = execResponse;
        this.state = 'active';
      }
    });
  }

  confirmOverrideRejected(): void {
    if (!this.overrideRecommendation) return;
    this.agent2.confirm({
      tradeId: this.overrideRecommendation.tradeId,
      action: 'CONFIRM',
    }).pipe(
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

  // ── Override panel — passed trade state ──────────────────────────────────

  toggleOverrideMode(): void {
    this.overrideMode = !this.overrideMode;
    if (!this.overrideMode) {
      this.resetOverrideState();
    } else if (this.tradeCard) {
      // Pre-fill with algorithm values so user can adjust from there
      this.overrideLots = this.tradeCard.lots;
      this.overrideT1   = this.tradeCard.thresholds.t1WatchNiftyLevel;
      this.overrideT2   = this.tradeCard.thresholds.t2ReadjustNiftyLevel;
      this.overrideT3   = this.tradeCard.thresholds.t3ExitNiftyLevel;
    }
  }

  checkMargin(): void {
    if (!this.tradeCard) return;
    this.marginResult  = null;
    this.marginLoading = true;
    this.agent5.checkMargin({
      tradeId:      this.tradeCard.tradeId,
      overrideLots: this.overrideLots,
    }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Margin check failed. Please try again.';
        return of(null);
      })
    ).subscribe(result => {
      this.marginLoading = false;
      if (result) this.marginResult = result;
    });
  }

  // ── Manual override builder ────────────────────────────────────────────────

  openBuilderMode(): void {
    if (!this.tradeCard) return;
    this.builderMode = true;
    this.overrideMetrics = null;
    // Pre-fill with the algorithm's rejected strikes as a starting point
    this.obPeShortStrike = this.tradeCard.shortLeg.strike;
    this.obPeLongStrike  = this.tradeCard.longLeg.strike;
    this.obCeShortStrike = this.tradeCard.shortLeg2?.strike ?? null;
    this.obCeLongStrike  = this.tradeCard.longLeg2?.strike ?? null;
    this.obLots          = this.tradeCard.lots;
    // Trigger an initial calculation so the user sees numbers immediately
    this.overrideCalcTrigger$.next();
  }

  onOverrideFieldChange(): void {
    this.overrideMetrics = null;
    this.overrideCalcTrigger$.next();
  }

  private runOverrideCalc(): void {
    if (!this.tradeCard
        || this.obPeShortStrike == null || this.obPeLongStrike == null
        || this.obLots == null || this.obLots < 1) {
      return;
    }
    this.overrideMetricsLoading = true;
    this.agent2.calculateOverride({
      tradeId:       this.tradeCard.tradeId,
      peShortStrike: this.obPeShortStrike,
      peLongStrike:  this.obPeLongStrike,
      ceShortStrike: this.obCeShortStrike,
      ceLongStrike:  this.obCeLongStrike,
      lots:          this.obLots,
    }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Override calculation failed. Check strike values.';
        this.overrideMetricsLoading = false;
        return of(null);
      })
    ).subscribe(result => {
      this.overrideMetricsLoading = false;
      if (result) this.overrideMetrics = result;
    });
  }

  get canConfirmOverrideBuilder(): boolean {
    if (!this.overrideMetrics) return false;
    return !this.overrideMetrics.popBlocked && !this.overrideMetrics.lossBlocked;
  }

  confirmOverrideBuilder(): void {
    if (!this.tradeCard || !this.overrideMetrics || !this.canConfirmOverrideBuilder) return;

    const overrideParams: OverrideParams = {
      peShortStrike:           this.obPeShortStrike!,
      peLongStrike:            this.obPeLongStrike!,
      ceShortStrike:           this.obCeShortStrike,
      ceLongStrike:            this.obCeLongStrike,
      lots:                    this.obLots!,
      peShortLtp:              this.overrideMetrics.peShortLtp,
      peLongLtp:               this.overrideMetrics.peLongLtp,
      ceShortLtp:              this.overrideMetrics.ceShortLtp,
      ceLongLtp:               this.overrideMetrics.ceLongLtp,
      peShortInstrumentKey:    this.overrideMetrics.peShortInstrumentKey,
      peLongInstrumentKey:     this.overrideMetrics.peLongInstrumentKey,
      ceShortInstrumentKey:    this.overrideMetrics.ceShortInstrumentKey,
      ceLongInstrumentKey:     this.overrideMetrics.ceLongInstrumentKey,
      netPremiumPerUnit:       this.overrideMetrics.netPremiumPerUnit,
      pop:                     this.overrideMetrics.pop,
      maxProfitTotal:          this.overrideMetrics.maxProfitTotal,
      theoreticalMaxLossTotal: this.overrideMetrics.theoreticalMaxLossTotal,
      realExpectedLossTotal:   this.overrideMetrics.realExpectedLossTotal,
      roc:                     this.overrideMetrics.roc,
    };

    this.agent2.confirm({
      tradeId: this.tradeCard.tradeId,
      action: 'CONFIRM',
      overrideParams,
    }).pipe(
      switchMap(card => {
        this.tradeCard = card;
        this.builderMode = false;
        return this.agent5.execute(this.buildExecuteRequest(card));
      }),
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Confirm or execution failed.';
        return of(null);
      })
    ).subscribe(execResponse => {
      if (execResponse) {
        this.executionResponse = execResponse;
        this.state = 'active';
      }
    });
  }

  closeBuilderMode(): void {
    this.builderMode = false;
    this.overrideMetrics = null;
  }

  // ── Override panel — rejected trade state (legacy relaxed re-run) ──────────

  triggerRejectedOverride(): void {
    if (!this.freshSignal || !this.tradeCard) return;
    this.overrideRecommendation = null;
    this.overrideLoading = true;
    this.errorMessage = null;

    // Re-run Agent 2 with relaxed Gate 1 PoP floor (65% instead of 80%)
    this.agent2.recommend({
      agent1SignalId:   this.freshSignal.id,
      userProfileId:    this.userState.userProfileId!,
      relaxedGate1PopPct: OVERRIDE_RELAXED_POP_FLOOR,
    }).pipe(
      catchError(err => {
        this.errorMessage = err?.error?.detail ?? 'Override recommendation failed. Please try again.';
        this.overrideLoading = false;
        return of(null);
      })
    ).subscribe(card => {
      this.overrideLoading = false;
      if (card) {
        this.overrideRecommendation = card;
        // Check margin automatically after getting the new card
        if (card.status !== 'REJECTED') {
          this.checkMarginForOverrideRejected(card);
        }
      }
    });
  }

  private checkMarginForOverrideRejected(card: TradeCard): void {
    this.marginLoading = true;
    this.agent5.checkMargin({ tradeId: card.tradeId }).pipe(
      catchError(() => of(null))
    ).subscribe(result => {
      this.marginLoading = false;
      if (result) this.marginResult = result;
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private buildExecuteRequest(card: TradeCard): ExecuteTradeRequest {
    const qty = card.lots * card.lotSize;
    const leg = (l: typeof card.shortLeg): LegOrderRequest => ({
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

  private buildOverrideThresholds(): OverrideThresholds | null {
    if (!this.overrideMode) return null;
    const hasAny = this.overrideT1 !== null || this.overrideT2 !== null || this.overrideT3 !== null;
    if (!hasAny) return null;
    return {
      t1WatchNiftyLevel:    this.overrideT1,
      t2ReadjustNiftyLevel: this.overrideT2,
      t3ExitNiftyLevel:     this.overrideT3,
    };
  }

  private resetOverrideState(): void {
    this.executionResponse      = null;
    this.overrideMode           = false;
    this.overrideLots           = null;
    this.overrideT1             = null;
    this.overrideT2             = null;
    this.overrideT3             = null;
    this.marginResult           = null;
    this.marginLoading          = false;
    this.overrideRecommendation = null;
    this.overrideLoading        = false;
    this.builderMode            = false;
    this.obPeShortStrike        = null;
    this.obPeLongStrike         = null;
    this.obCeShortStrike        = null;
    this.obCeLongStrike         = null;
    this.obLots                 = null;
    this.overrideMetrics        = null;
    this.overrideMetricsLoading = false;
  }

  reset(): void {
    this.state = 'awaiting-view';
    this.tradeCard = null;
    this.freshSignal = null;
    this.errorMessage = null;
    this.commentary = '';
    this.resetOverrideState();
  }

  // ── Display helpers ───────────────────────────────────────────────────────

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
