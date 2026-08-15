import { Component, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EMPTY } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ActiveTrade, MarginUtilization } from '../../core/models/trade.model';
import { PortfolioSummary } from '../../core/models/audit.models';
import { ThresholdBarComponent } from '../../shared/components/threshold-bar/threshold-bar.component';
import { AuditService } from '../../core/services/audit.service';
import { Agent5Service } from '../../core/services/agent5.service';

@Component({
  selector: 'app-live-monitor',
  standalone: true,
  imports: [CommonModule, ThresholdBarComponent],
  templateUrl: './live-monitor.component.html',
  styleUrls: ['./live-monitor.component.scss'],
})
export class LiveMonitorComponent implements OnInit {
  @Input() trades: ActiveTrade[] = [];

  todaySummary: PortfolioSummary | null = null;
  wtdSummary: PortfolioSummary | null = null;
  mtdSummary: PortfolioSummary | null = null;
  marginData: MarginUtilization | null = null;
  marginError = false;

  constructor(private audit: AuditService, private agent5: Agent5Service) {}

  ngOnInit(): void {
    const today = this.todayIso();
    const monday = this.mondayOfWeekIso();
    const firstOfMonth = this.firstOfMonthIso();

    this.audit.getSummary(today, today).pipe(catchError(() => EMPTY))
      .subscribe(s => (this.todaySummary = s));
    this.audit.getSummary(monday, today).pipe(catchError(() => EMPTY))
      .subscribe(s => (this.wtdSummary = s));
    this.audit.getSummary(firstOfMonth, today).pipe(catchError(() => EMPTY))
      .subscribe(s => (this.mtdSummary = s));
    this.agent5.marginUtilization().pipe(catchError(() => { this.marginError = true; return EMPTY; }))
      .subscribe(m => (this.marginData = m));
  }

  // P&L summary getters

  get todayClosedPnl(): number | null { return this.todaySummary?.totalRealizedPnl ?? null; }
  get todayTradeCount(): number { return this.todaySummary?.totalTrades ?? 0; }

  get wtdPnl(): number | null {
    if (!this.wtdSummary) return null;
    return this.wtdSummary.totalRealizedPnl + this.openPnl;
  }
  get wtdClosedCount(): number { return this.wtdSummary?.totalTrades ?? 0; }

  get mtdPnl(): number | null {
    if (!this.mtdSummary) return null;
    return this.mtdSummary.totalRealizedPnl + this.openPnl;
  }
  get mtdClosedCount(): number { return this.mtdSummary?.totalTrades ?? 0; }

  get utilizationPct(): number { return this.marginData?.utilizationPct ?? 0; }

  get utilizationBarColor(): string {
    const pct = this.utilizationPct;
    if (pct > 80) return '#DC2626';
    if (pct > 60) return '#D97706';
    return '#2563EB';
  }

  // Trade card helpers

  warningClass(trade: ActiveTrade): string {
    const hit = trade.lastThresholdHit;
    if (hit === 'T3') return 'warn-t3';
    if (hit === 'T2') return 'warn-t2';
    if (hit === 'T1') return 'warn-t1';
    return '';
  }

  alertTag(trade: ActiveTrade): { label: string; cssClass: string } | null {
    const hit = trade.lastThresholdHit;
    if (hit === 'T3') return { label: 'T3 EXIT', cssClass: 'alert-t3' };
    if (hit === 'T2') return { label: 'T2 READJUST', cssClass: 'alert-t2' };
    if (hit === 'T1') return { label: 'T1 WATCH', cssClass: 'alert-t1' };
    return null;
  }

  isIronCondor(trade: ActiveTrade): boolean {
    return !!(trade.monitorConfig?.shortLeg2);
  }

  private liveLevel(trade: ActiveTrade, key: string): number | null {
    const v = trade.liveThresholds?.[key];
    return typeof v === 'number' ? v : null;
  }

  hasLiveLevels(trade: ActiveTrade): boolean {
    const lt = trade.liveThresholds;
    return !!lt && (lt['liveT1NiftyLevel'] != null || lt['liveT1NiftyDown'] != null
      || lt['liveLossCutLevel'] != null || lt['liveProfitBookLevel'] != null);
  }

  isDebit(trade: ActiveTrade): boolean {
    return trade.monitorConfig?.spreadDirection === 'DEBIT';
  }
  // Breakeven and Profit-Book are fixed at entry and stored in monitor_config, so fall back to the
  // static thresholds when no live cycle has run yet (e.g. trade created after market close).
  //   debitSpread thresholds: t1WatchNiftyLevel = breakeven, t2ReadjustNiftyLevel = short strike (profit-book).
  // Loss-Cut is dynamic (recomputed each cycle from live IV/DTE) — no static equivalent, so it stays
  // live-only rather than showing a stale entry-time value.
  debitBreakeven(trade: ActiveTrade): number | null {
    return this.liveLevel(trade, 'liveBreakevenLevel')
      ?? (trade.monitorConfig?.thresholds?.t1WatchNiftyLevel ?? null);
  }
  debitProfitBook(trade: ActiveTrade): number | null {
    return this.liveLevel(trade, 'liveProfitBookLevel')
      ?? (trade.monitorConfig?.thresholds?.t2ReadjustNiftyLevel ?? null);
  }
  debitLossCut(trade: ActiveTrade): number | null { return this.liveLevel(trade, 'liveLossCutLevel'); }
  private livePct(trade: ActiveTrade, key: string): number | null {
    const v = this.liveLevel(trade, key);
    return v == null ? null : v * 100;
  }
  debitPopPct(trade: ActiveTrade): number | null { return this.livePct(trade, 'livePop'); }
  debitPoppPct(trade: ActiveTrade): number | null { return this.livePct(trade, 'livePopp'); }
  debitGapPct(trade: ActiveTrade): number | null { return this.livePct(trade, 'liveGap'); }

  t1Level(trade: ActiveTrade, side: 'down' | 'up' = 'down'): number | null {
    const t = trade.monitorConfig?.thresholds;
    if (this.isIronCondor(trade)) {
      return this.liveLevel(trade, side === 'down' ? 'liveT1NiftyDown' : 'liveT1NiftyUp')
        ?? (side === 'down' ? (t?.t1WatchNiftyDown ?? null) : (t?.t1WatchNiftyUp ?? null));
    }
    return this.liveLevel(trade, 'liveT1NiftyLevel') ?? (t?.t1WatchNiftyLevel ?? null);
  }

  t2Level(trade: ActiveTrade, side: 'down' | 'up' = 'down'): number | null {
    const t = trade.monitorConfig?.thresholds;
    if (this.isIronCondor(trade)) {
      return this.liveLevel(trade, side === 'down' ? 'liveT2NiftyDown' : 'liveT2NiftyUp')
        ?? (side === 'down' ? (t?.t2ReadjustNiftyDown ?? null) : (t?.t2ReadjustNiftyUp ?? null));
    }
    return this.liveLevel(trade, 'liveT2NiftyLevel') ?? (t?.t2ReadjustNiftyLevel ?? null);
  }

  t3Level(trade: ActiveTrade, side: 'down' | 'up' = 'down'): number | null {
    const t = trade.monitorConfig?.thresholds;
    if (this.isIronCondor(trade)) {
      return this.liveLevel(trade, side === 'down' ? 'liveT3NiftyDown' : 'liveT3NiftyUp')
        ?? (side === 'down' ? (t?.t3ExitNiftyDown ?? null) : (t?.t3ExitNiftyUp ?? null));
    }
    return this.liveLevel(trade, 'liveT3NiftyLevel') ?? (t?.t3ExitNiftyLevel ?? null);
  }

  alertMessage(trade: ActiveTrade): string | null {
    const hit = trade.lastThresholdHit;
    if (!hit || hit === 'NONE') return null;
    const t = trade.monitorConfig?.thresholds;
    if (hit === 'T1') {
      if (this.isIronCondor(trade)) {
        return `Spot approaching T1 watch levels (PE: ${t?.t1WatchNiftyDown?.toFixed(0) ?? '-'} / CE: ${t?.t1WatchNiftyUp?.toFixed(0) ?? '-'}). Monitor closely.`;
      }
      return `Spot approaching T1 watch level (${t?.t1WatchNiftyLevel?.toFixed(0) ?? '-'}). Monitor closely.`;
    }
    if (hit === 'T2') return `T2 breach - consider readjustment. MtM loss >= 50% of max.`;
    if (hit === 'T3') return `T3 EXIT triggered. Agent 5 closing position.`;
    return null;
  }

  thresholdProgress(trade: ActiveTrade, level: 'T1' | 'T2' | 'T3', side: 'down' | 'up' = 'down'): number {
    const spot = trade.spotPrice;
    const thresholds = trade.monitorConfig?.thresholds;
    if (spot == null || !thresholds) return 0;

    const t1 = this.t1Level(trade, side);
    const t3 = this.t3Level(trade, side);
    if (t1 == null || t3 == null) return 0;

    const range = Math.abs(t3 - t1);
    if (range === 0) return 0;

    const raw = side === 'up' ? (spot - t1) / range * 100 : (t1 - spot) / range * 100;
    return Math.min(100, Math.max(0, raw));
  }

  thresholdVariant(trade: ActiveTrade, level: 'T1' | 'T2' | 'T3'): 'safe' | 'caution' | 'danger' {
    const hit = trade.lastThresholdHit;
    if (hit === 'T3') return 'danger';
    if (hit === 'T2') return level === 'T1' ? 'caution' : 'danger';
    if (hit === 'T1') return level === 'T1' ? 'caution' : 'safe';
    return 'safe';
  }

  pnlColor(pnl: number | null): string {
    if (pnl == null) return '#0F172A';
    return pnl >= 0 ? '#16A34A' : '#DC2626';
  }

  pnlPctClass(pnl: number | null): string {
    if (pnl == null) return '';
    return pnl >= 0 ? 'pnl-pct-pos' : 'pnl-pct-neg';
  }

  pnlPct(trade: ActiveTrade): string {
    const pnl = trade.markToMarketPnl;
    const config = trade.monitorConfig;
    if (pnl == null || !config) return '';
    const maxProfit = config.maxProfitTotal;
    if (!maxProfit || maxProfit === 0) return '';
    const pct = (pnl / maxProfit) * 100;
    return `${pct >= 0 ? '+ ' : ''}${pct.toFixed(1)}%`;
  }

  distance(trade: ActiveTrade): number | null {
    const spot = trade.spotPrice;
    const config = trade.monitorConfig;
    if (spot == null || !config) return null;
    const shortStrike = config.shortLeg?.strike;
    if (!shortStrike) return null;
    return shortStrike - spot;
  }

  // Iron Condor is two-sided: show both short strikes and the buffer (room) to each short.
  peShortStrike(trade: ActiveTrade): number | null { return trade.monitorConfig?.shortLeg?.strike ?? null; }
  ceShortStrike(trade: ActiveTrade): number | null { return trade.monitorConfig?.shortLeg2?.strike ?? null; }

  /** Room above the PE short (positive = spot inside the condor, safe; negative = PE short breached). */
  icDistancePe(trade: ActiveTrade): number | null {
    const s = trade.spotPrice, k = this.peShortStrike(trade);
    return s == null || k == null ? null : s - k;
  }
  /** Room below the CE short (positive = spot inside the condor, safe; negative = CE short breached). */
  icDistanceCe(trade: ActiveTrade): number | null {
    const s = trade.spotPrice, k = this.ceShortStrike(trade);
    return s == null || k == null ? null : k - s;
  }

  formatInr(val: number | null | undefined): string {
    if (val == null) return '—';
    const sign = val >= 0 ? '+ ₹ ' : '− ₹ ';
    return `${sign}${Math.abs(val).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
  }

  formatInrAbs(val: number | null | undefined): string {
    if (val == null) return '—';
    return `₹ ${val.toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
  }

  get openPnl(): number {
    return this.trades.reduce((sum, t) => sum + (t.markToMarketPnl ?? 0), 0);
  }

  strategyLabel(s: string | undefined): string {
    return s?.replace(/_/g, ' ') ?? '-';
  }

  timeAgo(iso: string | undefined): string {
    if (!iso) return '';
    const diffMs = Date.now() - new Date(iso).getTime();
    const hrs = diffMs / 3600000;
    if (hrs < 1 / 60) return 'just now';
    if (hrs < 1) return `${Math.floor(diffMs / 60000)} min ago`;
    return `${hrs.toFixed(1)} hrs ago`;
  }

  dteLabel(expiryDate: string | undefined): string {
    if (!expiryDate) return '-';
    const dte = Math.ceil((new Date(expiryDate).getTime() - Date.now()) / 86400000);
    return `DTE ${Math.max(0, dte)}`;
  }

  // Date helpers

  private todayIso(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private mondayOfWeekIso(): string {
    const d = new Date();
    const day = d.getDay();
    const diffToMonday = day === 0 ? -6 : 1 - day;
    d.setDate(d.getDate() + diffToMonday);
    return d.toISOString().slice(0, 10);
  }

  private firstOfMonthIso(): string {
    const d = new Date();
    d.setDate(1);
    return d.toISOString().slice(0, 10);
  }
}
