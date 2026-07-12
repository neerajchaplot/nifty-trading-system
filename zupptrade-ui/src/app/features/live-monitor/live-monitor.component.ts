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

  isIronCondor(trade: ActiveTrade): boolean {
    return !!(trade.monitorConfig?.shortLeg2);
  }

  t1Level(trade: ActiveTrade, side: 'down' | 'up' = 'down'): number | null {
    const t = trade.monitorConfig?.thresholds;
    if (!t) return null;
    if (this.isIronCondor(trade)) {
      return side === 'down' ? (t.t1WatchNiftyDown ?? null) : (t.t1WatchNiftyUp ?? null);
    }
    return t.t1WatchNiftyLevel ?? null;
  }

  t2Level(trade: ActiveTrade, side: 'down' | 'up' = 'down'): number | null {
    const t = trade.monitorConfig?.thresholds;
    if (!t) return null;
    if (this.isIronCondor(trade)) {
      return side === 'down' ? (t.t2ReadjustNiftyDown ?? null) : (t.t2ReadjustNiftyUp ?? null);
    }
    return t.t2ReadjustNiftyLevel ?? null;
  }

  t3Level(trade: ActiveTrade, side: 'down' | 'up' = 'down'): number | null {
    const t = trade.monitorConfig?.thresholds;
    if (!t) return null;
    if (this.isIronCondor(trade)) {
      return side === 'down' ? (t.t3ExitNiftyDown ?? null) : (t.t3ExitNiftyUp ?? null);
    }
    return t.t3ExitNiftyLevel ?? null;
  }

  alertMessage(trade: ActiveTrade): string | null {
    const hit = trade.lastThresholdHit;
    if (!hit || hit === 'NONE') return null;
    const t = trade.monitorConfig?.thresholds;
    if (hit === 'T1') {
      if (this.isIronCondor(trade)) {
        return `Spot approaching T1 watch levels (PE: ${t?.t1WatchNiftyDown?.toFixed(0) ?? '—'} / CE: ${t?.t1WatchNiftyUp?.toFixed(0) ?? '—'}). Monitor closely.`;
      }
      return `Spot approaching T1 watch level (${t?.t1WatchNiftyLevel?.toFixed(0) ?? '—'}). Monitor closely.`;
    }
    if (hit === 'T2') return `T2 breach — consider readjustment. MtM loss ≥ 50% of max.`;
    if (hit === 'T3') return `T3 EXIT triggered. Agent 5 closing position.`;
    return null;
  }

  /**
   * Returns 0–100 progress toward a threshold level.
   * side='down': spot falling toward t3 (PE risk). side='up': spot rising toward t3 (CE risk).
   * 0% = spot at T1 (just entering watch zone), 100% = spot at T3 (exit level).
   */
  thresholdProgress(trade: ActiveTrade, level: 'T1' | 'T2' | 'T3', side: 'down' | 'up' = 'down'): number {
    const spot = trade.spotPrice;
    const thresholds = trade.monitorConfig?.thresholds;
    if (spot == null || !thresholds) return 0;

    const t1 = this.t1Level(trade, side);
    const t3 = this.t3Level(trade, side);
    if (t1 == null || t3 == null) return 0;

    const range = Math.abs(t3 - t1);
    if (range === 0) return 0;

    // For 'down': spot falls from t1 toward t3 (t3 < t1). Progress = (t1 - spot) / range.
    // For 'up':  spot rises from t1 toward t3 (t3 > t1). Progress = (spot - t1) / range.
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

  timeAgo(iso: string | undefined): string {
    if (!iso) return '';
    const diffMs = Date.now() - new Date(iso).getTime();
    const hrs = diffMs / 3600000;
    if (hrs < 1 / 60) return 'just now';
    if (hrs < 1) return `${Math.floor(diffMs / 60000)} min ago`;
    return `${hrs.toFixed(1)} hrs ago`;
  }

  dteLabel(expiryDate: string | undefined): string {
    if (!expiryDate) return '—';
    const dte = Math.ceil((new Date(expiryDate).getTime() - Date.now()) / 86400000);
    return `DTE ${Math.max(0, dte)}`;
  }
}
