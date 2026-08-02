import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { catchError, of } from 'rxjs';
import { FuturesAnalyticsService } from '../../core/services/futures-analytics.service';
import { FuturesPnlResponse, FuturesPnlRow } from '../../core/models/futures-analytics.model';
import { FutureArmType } from '../../core/models/enums';

/**
 * Agent 4 futures P&L filter: closed futures trades with simple counts and a color-coded +/− P&L
 * column. Each row's hover shows the Agent 1 recommendation. Shown inside the Audit tab's
 * Options|Futures toggle; reuses the tab's date range.
 */
@Component({
  selector: 'app-futures-audit',
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Summary counts -->
    <div class="fa-summary" *ngIf="data as d">
      <div class="fa-kpi"><div class="fa-val">{{ d.summary.tradeCount }}</div><div class="fa-lbl">Trades</div></div>
      <div class="fa-kpi"><div class="fa-val fa-win">{{ d.summary.winCount }}</div><div class="fa-lbl">Wins</div></div>
      <div class="fa-kpi"><div class="fa-val fa-loss">{{ d.summary.lossCount }}</div><div class="fa-lbl">Losses</div></div>
      <div class="fa-kpi">
        <div class="fa-val" [class.fa-win]="d.summary.totalRealizedPnl > 0" [class.fa-loss]="d.summary.totalRealizedPnl < 0">
          {{ pnl(d.summary.totalRealizedPnl) }}
        </div>
        <div class="fa-lbl">Net P&L</div>
      </div>
    </div>

    <div class="fa-empty" *ngIf="loaded && data && data.trades.length === 0">
      No closed futures trades in this range.
    </div>

    <!-- Trades table -->
    <div class="fa-table" *ngIf="data && data.trades.length > 0">
      <div class="fa-head">
        <span>Date</span><span>Trade</span><span>Entry</span><span>Stop</span><span>Target</span>
        <span>Exit reason</span><span class="fa-r">P&L</span>
      </div>
      <div class="fa-row" *ngFor="let t of data.trades; trackBy: trackRow" [title]="hover(t)">
        <span class="mono">{{ t.tradeDate }}</span>
        <span class="fa-trade">{{ label(t.armType) }}</span>
        <span class="mono">{{ fmt(t.entry) }}</span>
        <span class="mono fa-loss">{{ fmt(t.stop) }}</span>
        <span class="mono fa-win">{{ fmt(t.target) }}</span>
        <span>{{ t.closeReason }}</span>
        <span class="fa-r fa-pnl" [class.fa-win]="t.realizedPnl > 0" [class.fa-loss]="t.realizedPnl < 0">
          {{ pnl(t.realizedPnl) }}
        </span>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; padding: 12px 20px; overflow: auto; }
    .fa-summary { display: flex; gap: 12px; margin-bottom: 14px; }
    .fa-kpi { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 10px 18px; text-align: center; min-width: 90px; }
    .fa-val { font-size: 20px; font-weight: 800; color: #0f172a; }
    .fa-lbl { font-size: 10px; color: #94a3b8; font-weight: 600; text-transform: uppercase; letter-spacing: .4px; }
    .fa-win { color: #16a34a; }
    .fa-loss { color: #dc2626; }
    .fa-empty { color: #94a3b8; font-size: 13px; padding: 20px 0; }
    .fa-table { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; overflow: hidden; }
    .fa-head, .fa-row {
      display: grid;
      grid-template-columns: 110px 1.4fr 90px 90px 90px 1.2fr 110px;
      align-items: center; gap: 8px; padding: 9px 14px;
    }
    .fa-head { background: #f8fafc; font-size: 10px; text-transform: uppercase; letter-spacing: .3px; color: #94a3b8; font-weight: 700; }
    .fa-row { border-top: 1px solid #f1f5f9; font-size: 13px; color: #0f172a; cursor: default; }
    .fa-row:hover { background: #f8fafc; }
    .fa-trade { font-weight: 700; }
    .fa-r { text-align: right; }
    .fa-pnl { font-weight: 800; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  `],
})
export class FuturesAuditComponent implements OnChanges {
  @Input() from?: string;
  @Input() to?: string;

  data: FuturesPnlResponse | null = null;
  loaded = false;

  private static readonly LABELS: Record<FutureArmType, string> = {
    LONG_ROTATION: 'Buy the dip',
    SHORT_ROTATION: 'Sell the rise',
    LONG_BREAKOUT: 'Breakout buy',
    SHORT_BREAKDOWN: 'Breakdown sell',
  };

  constructor(private readonly svc: FuturesAnalyticsService) {}

  ngOnChanges(): void {
    this.load();
  }

  private load(): void {
    this.loaded = false;
    this.svc.getFuturesPnl(this.from, this.to)
      .pipe(catchError(() => of(null)))
      .subscribe(d => { this.data = d; this.loaded = true; });
  }

  label(arm: FutureArmType): string {
    return FuturesAuditComponent.LABELS[arm] ?? arm;
  }

  hover(t: FuturesPnlRow): string {
    return `Agent 1: ${t.bias} · ${t.confidenceLabel} confidence → ${this.label(t.armType)}`;
  }

  fmt(n: number | null | undefined): string {
    return n == null ? '—' : n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  }

  pnl(n: number | null | undefined): string {
    if (n == null) return '—';
    const sign = n > 0 ? '+' : '';
    return sign + '₹' + Math.round(n).toLocaleString('en-IN');
  }

  trackRow = (_: number, t: FuturesPnlRow): string => t.planCode;
}
