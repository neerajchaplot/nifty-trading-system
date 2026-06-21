import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActiveTrade } from '../../core/models/trade.model';
import { ThresholdBarComponent } from '../../shared/components/threshold-bar/threshold-bar.component';
import { ThresholdHit } from '../../core/models/enums';

@Component({
  selector: 'app-live-monitor',
  standalone: true,
  imports: [CommonModule, ThresholdBarComponent],
  templateUrl: './live-monitor.component.html',
  styleUrls: ['./live-monitor.component.scss'],
})
export class LiveMonitorComponent {
  @Input() trades: ActiveTrade[] = [];

  readonly MAX_SLOTS = 3;

  get emptySlots(): number[] {
    return Array(Math.max(0, this.MAX_SLOTS - this.trades.length)).fill(0);
  }

  warningClass(trade: ActiveTrade): string {
    const hit = trade.lastThresholdHit;
    if (hit === 'T3') return 'warn-t3';
    if (hit === 'T2') return 'warn-t2';
    if (hit === 'T1') return 'warn-t1';
    return '';
  }

  alertTag(trade: ActiveTrade): { label: string; cssClass: string } | null {
    const hit = trade.lastThresholdHit;
    if (hit === 'T3') return { label: '🚨 T3 EXIT', cssClass: 'alert-t3' };
    if (hit === 'T2') return { label: '⚠ T2 READJUST', cssClass: 'alert-t2' };
    if (hit === 'T1') return { label: '⚠ T1 WATCH', cssClass: 'alert-t1' };
    return null;
  }

  alertMessage(trade: ActiveTrade): string | null {
    const hit = trade.lastThresholdHit;
    if (!hit || hit === 'NONE') return null;
    const level = trade.monitorConfig?.thresholds;
    if (hit === 'T1') return `Spot approaching T1 watch level (${level?.t1WatchNiftyLevel?.toFixed(0) ?? '—'}). Monitor closely.`;
    if (hit === 'T2') return `T2 breach — consider readjustment. MtM loss ≥ 50% of max.`;
    if (hit === 'T3') return `T3 EXIT triggered. Agent 5 closing position.`;
    return null;
  }

  /**
   * Returns 0–100 progress of each threshold bar based on how close spot is to that level.
   * Entry spot is used as the safe anchor (0%), short strike as T3 (100%).
   */
  thresholdProgress(trade: ActiveTrade, level: 'T1' | 'T2' | 'T3'): number {
    const spot = trade.spotPrice;
    const thresholds = trade.monitorConfig?.thresholds;
    if (!spot || !thresholds) return 0;

    const t3 = thresholds.t3ExitNiftyLevel;
    const t1 = thresholds.t1WatchNiftyLevel;
    if (!t3 || !t1) return 0;

    const range = Math.abs(t3 - t1);
    if (range === 0) return 0;

    const target = level === 'T1' ? thresholds.t1WatchNiftyLevel
      : level === 'T2' ? thresholds.t2ReadjustNiftyLevel
      : thresholds.t3ExitNiftyLevel;

    if (!target) return 0;

    const progress = Math.abs(spot - t1) / range * 100;
    return Math.min(100, Math.max(0, progress));
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
    return `${pct >= 0 ? '▲ +' : '▼ '}${pct.toFixed(1)}%`;
  }

  distance(trade: ActiveTrade): number | null {
    const spot = trade.spotPrice;
    const config = trade.monitorConfig;
    if (spot == null || !config) return null;
    const shortStrike = config.shortLeg?.strike;
    if (!shortStrike) return null;
    return shortStrike - spot;
  }

  formatInr(val: number | null | undefined): string {
    if (val == null) return '—';
    const sign = val >= 0 ? '+ ₹ ' : '− ₹ ';
    return `${sign}${Math.abs(val).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
  }

  get openPnl(): number {
    return this.trades.reduce((sum, t) => sum + (t.markToMarketPnl ?? 0), 0);
  }

  strategyLabel(s: string | undefined): string {
    return s?.replace(/_/g, ' ') ?? '—';
  }

  dteLabel(expiryDate: string | undefined): string {
    if (!expiryDate) return '—';
    const dte = Math.ceil((new Date(expiryDate).getTime() - Date.now()) / 86400000);
    return `DTE ${Math.max(0, dte)}`;
  }
}
