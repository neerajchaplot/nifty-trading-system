import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuditService } from '../../core/services/audit.service';
import {
  PortfolioSummary,
  TradeListItem,
  TradeListResponse,
  SignalQuality,
} from '../../core/models/audit.models';
import { TradeAuditDrawerComponent } from './trade-audit-drawer.component';

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [CommonModule, FormsModule, TradeAuditDrawerComponent],
  template: `
    <!-- Toolbar -->
    <div class="toolbar">
      <span class="section-label">Performance Audit</span>
      <div class="date-range">
        <label>From</label>
        <input type="date" [(ngModel)]="fromDate" (change)="onFilterChange()" />
        <label>To</label>
        <input type="date" [(ngModel)]="toDate" (change)="onFilterChange()" />
        <button class="btn-reset" (click)="clearFilters()">Clear</button>
      </div>
    </div>

    <!-- KPI cards -->
    <div class="kpi-row" *ngIf="summary">
      <div class="kpi-card">
        <div class="kpi-val" [class.kpi-win]="(summary.winRatePct ?? 0) >= 60"
                             [class.kpi-loss]="(summary.winRatePct ?? 0) < 50">
          {{ fmt1(summary.winRatePct) }}%
        </div>
        <div class="kpi-lbl">Win Rate</div>
        <div class="kpi-sub">{{ summary.winCount }}W / {{ summary.lossCount }}L of {{ summary.totalTrades }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-val" [class.kpi-win]="(summary.totalRealizedPnl ?? 0) > 0"
                             [class.kpi-loss]="(summary.totalRealizedPnl ?? 0) < 0">
          {{ fmtPnl(summary.totalRealizedPnl) }}
        </div>
        <div class="kpi-lbl">Total Realized P&L</div>
        <div class="kpi-sub">Avg {{ fmt2(summary.avgRocAchievedPct) }}% RoC</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-val"
             [class.kpi-win]="(summary.rocCaptureRatioPct ?? 0) >= 70"
             [class.kpi-neutral]="(summary.rocCaptureRatioPct ?? 0) >= 50 && (summary.rocCaptureRatioPct ?? 0) < 70"
             [class.kpi-loss]="(summary.rocCaptureRatioPct ?? 0) < 50">
          {{ fmt1(summary.rocCaptureRatioPct) }}%
        </div>
        <div class="kpi-lbl">RoC Capture</div>
        <div class="kpi-sub">{{ fmt2(summary.avgRocTheoreticalPct) }}% theoretical</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-val"
             [class.kpi-win]="(summary.agent1AccuracyPct ?? 0) >= 60"
             [class.kpi-neutral]="(summary.agent1AccuracyPct ?? 0) >= 45 && (summary.agent1AccuracyPct ?? 0) < 60"
             [class.kpi-loss]="(summary.agent1AccuracyPct ?? 0) < 45">
          {{ fmt1(summary.agent1AccuracyPct) }}%
        </div>
        <div class="kpi-lbl">Agent 1 Accuracy</div>
        <div class="kpi-sub">Signal quality</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-val kpi-loss">{{ fmtPnl(summary.maxDrawdown) }}</div>
        <div class="kpi-lbl">Max Drawdown</div>
        <div class="kpi-sub">Worst: {{ fmtPnl(summary.maxLossSingleTrade) }} single</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-val"
             [class.kpi-win]="(summary.adjustmentRecoveryRatePct ?? 0) >= 50"
             [class.kpi-loss]="(summary.adjustmentRecoveryRatePct ?? 0) < 50">
          {{ fmt1(summary.adjustmentRecoveryRatePct) }}%
        </div>
        <div class="kpi-lbl">Adjustment Recovery</div>
        <div class="kpi-sub">{{ summary.totalAdjustments }} total adjustments</div>
      </div>
    </div>
    <div class="kpi-row kpi-loading" *ngIf="!summary && !summaryError">
      <div class="loading-bar"></div>
    </div>
    <div class="kpi-error" *ngIf="summaryError">{{ summaryError }}</div>

    <!-- Strategy + VIX mix mini-panels -->
    <div class="mix-row" *ngIf="summary">
      <div class="mix-panel">
        <div class="mix-title">Strategy Mix</div>
        <div class="mix-items">
          <div class="mix-item" *ngFor="let e of strategyEntries()">
            <span class="mix-key">{{ e[0] }}</span>
            <div class="mix-bar-bg"><div class="mix-bar" [style.width.%]="barPct(e[1], summary!.totalTrades)"></div></div>
            <span class="mix-count">{{ e[1] }}</span>
          </div>
        </div>
      </div>
      <div class="mix-panel">
        <div class="mix-title">Win Rate by VIX Regime</div>
        <div class="mix-items">
          <div class="mix-item" *ngFor="let e of vixWinEntries()">
            <span class="mix-key">{{ e[0] }}</span>
            <div class="mix-bar-bg"><div class="mix-bar mix-bar-win" [style.width.%]="e[1]"></div></div>
            <span class="mix-count">{{ fmt1(e[1]) }}%</span>
          </div>
        </div>
      </div>
      <div class="mix-panel" *ngIf="signalQuality">
        <div class="mix-title">Signal Accuracy by Confidence</div>
        <div class="mix-items">
          <div class="mix-item" *ngFor="let e of accByConfEntries()">
            <span class="mix-key">{{ e[0] }}</span>
            <div class="mix-bar-bg"><div class="mix-bar mix-bar-win" [style.width.%]="e[1]"></div></div>
            <span class="mix-count">{{ fmt1(e[1]) }}%</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Trade table -->
    <div class="table-section">
      <div class="table-header">
        <span class="table-title">Trades</span>
        <span class="table-count" *ngIf="totalCount != null">{{ trades.length }} / {{ totalCount }}</span>
      </div>

      <div class="table-wrap">
        <table class="trade-table">
          <thead>
            <tr>
              <th>Code</th>
              <th>Expiry</th>
              <th>Strategy</th>
              <th>Signal</th>
              <th>Entry</th>
              <th>Days</th>
              <th>Lots</th>
              <th>Net Prem</th>
              <th>P&L</th>
              <th>RoC%</th>
              <th>Adj</th>
              <th>Close Reason</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let t of trades"
                class="trade-row"
                [class.outcome-win]="t.outcome === 'WIN'"
                [class.outcome-loss]="t.outcome === 'LOSS'"
                [class.outcome-open]="t.outcome === 'OPEN'"
                [class.selected-row]="selectedTrade?.tradeId === t.tradeId"
                (click)="openDrawer(t)">
              <td class="td-code">
                <span class="trade-code-link">{{ t.tradeCode }}</span>
              </td>
              <td class="mono">{{ t.expiryDate }}</td>
              <td>
                <span class="strategy-tag">{{ abbreviate(t.strategy) }}</span>
                <span class="dir-tag" [class.dir-sell]="t.spreadDirection === 'SELL'" [class.dir-buy]="t.spreadDirection === 'BUY'">
                  {{ t.spreadDirection }}
                </span>
              </td>
              <td>
                <span class="bias-pill" [class]="biasCss(t.signalBias)">{{ t.signalBias?.charAt(0) }}</span>
                <span class="strength-tag">{{ t.signalStrength?.charAt(0) }}</span>
                <span class="score-tag">{{ fmt2(t.signalScore) }}</span>
              </td>
              <td class="mono td-date">{{ fmtDate(t.entryDate) }}</td>
              <td class="mono center">{{ t.holdingDays ?? '—' }}</td>
              <td class="mono center">{{ t.lots }}</td>
              <td class="mono">₹{{ fmt2(t.entryNetPremium) }}</td>
              <td class="mono" [class.pnl-win]="t.actualPnl > 0" [class.pnl-loss]="t.actualPnl <= 0">
                {{ t.actualPnl != null ? (t.actualPnl >= 0 ? '+' : '') + fmtCr(t.actualPnl) : '—' }}
              </td>
              <td class="mono center"
                  [class.pnl-win]="(t.rocAchievedPct ?? 0) > 0"
                  [class.pnl-loss]="(t.rocAchievedPct ?? 0) < 0">
                {{ t.rocAchievedPct != null ? fmt2(t.rocAchievedPct) + '%' : '—' }}
              </td>
              <td class="mono center" [class.flag-warn]="t.adjustmentCount > 0">{{ t.adjustmentCount }}</td>
              <td class="td-reason">{{ t.closeReason ?? '—' }}</td>
            </tr>
            <tr *ngIf="trades.length === 0 && !tradesLoading">
              <td colspan="12" class="empty-cell">No trades found for the selected period.</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Load more / loading -->
      <div class="table-footer">
        <div class="loading-row" *ngIf="tradesLoading">
          <div class="dot-loader"><span></span><span></span><span></span></div>
        </div>
        <button class="btn-load-more"
                *ngIf="hasMore && !tradesLoading"
                (click)="loadMore()">
          Load more ({{ totalCount! - trades.length }} remaining)
        </button>
      </div>
    </div>

    <!-- Drawer overlay -->
    <ng-container *ngIf="selectedTrade">
      <app-trade-audit-drawer
        [trade]="selectedTrade"
        (close)="closeDrawer()">
      </app-trade-audit-drawer>
    </ng-container>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
      background: #F8FAFC;
    }
    /* Toolbar */
    .toolbar {
      display: flex; align-items: center; justify-content: space-between;
      padding: 10px 20px;
      border-bottom: 1px solid #E2E8F0;
      background: #fff;
      flex-shrink: 0;
    }
    .section-label { font-size: 13px; font-weight: 700; color: #0F172A; }
    .date-range { display: flex; align-items: center; gap: 8px; }
    .date-range label { font-size: 11px; color: #94A3B8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.3px; }
    .date-range input[type="date"] {
      border: 1px solid #E2E8F0; border-radius: 6px;
      padding: 4px 8px; font-size: 12px; color: #0F172A;
      background: #F8FAFC; outline: none;
    }
    .date-range input[type="date"]:focus { border-color: #2563EB; background: #fff; }
    .btn-reset {
      background: none; border: 1px solid #E2E8F0; border-radius: 6px;
      padding: 4px 10px; font-size: 11px; color: #64748B; cursor: pointer;
    }
    .btn-reset:hover { background: #F1F5F9; }
    /* KPI row */
    .kpi-row {
      display: flex; gap: 0;
      background: #fff;
      border-bottom: 1px solid #E2E8F0;
      flex-shrink: 0;
    }
    .kpi-loading { height: 72px; align-items: center; justify-content: center; }
    .kpi-card {
      flex: 1; padding: 12px 20px;
      border-right: 1px solid #F1F5F9;
    }
    .kpi-card:last-child { border-right: none; }
    .kpi-val { font-size: 22px; font-weight: 800; color: #0F172A; line-height: 1; }
    .kpi-lbl { font-size: 10px; color: #94A3B8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.4px; margin-top: 3px; }
    .kpi-sub { font-size: 10px; color: #64748B; margin-top: 2px; }
    .kpi-win  { color: #16A34A; }
    .kpi-loss { color: #DC2626; }
    .kpi-neutral { color: #D97706; }
    .kpi-error { padding: 12px 20px; font-size: 12px; color: #DC2626; }
    /* Loading bar */
    .loading-bar {
      height: 3px; width: 120px; background: #E2E8F0; border-radius: 2px; overflow: hidden; position: relative;
    }
    .loading-bar::after {
      content: ''; position: absolute; inset: 0;
      background: #2563EB; border-radius: 2px;
      animation: lbar 1.2s ease-in-out infinite;
    }
    @keyframes lbar {
      0%   { transform: translateX(-100%); }
      100% { transform: translateX(220%); }
    }
    /* Mix row */
    .mix-row {
      display: flex; gap: 0;
      background: #fff;
      border-bottom: 1px solid #E2E8F0;
      flex-shrink: 0;
    }
    .mix-panel { flex: 1; padding: 8px 20px; border-right: 1px solid #F1F5F9; }
    .mix-panel:last-child { border-right: none; }
    .mix-title { font-size: 10px; color: #94A3B8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 5px; }
    .mix-items { display: flex; flex-direction: column; gap: 3px; }
    .mix-item { display: flex; align-items: center; gap: 6px; font-size: 11px; }
    .mix-key { width: 110px; color: #475569; flex-shrink: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
    .mix-bar-bg { flex: 1; height: 4px; background: #F1F5F9; border-radius: 2px; overflow: hidden; }
    .mix-bar { height: 100%; background: #2563EB; border-radius: 2px; }
    .mix-bar-win { background: #16A34A; }
    .mix-count { width: 32px; text-align: right; color: #64748B; font-size: 10px; }
    /* Table section */
    .table-section {
      display: flex; flex-direction: column;
      flex: 1; overflow: hidden;
    }
    .table-header {
      display: flex; align-items: center; gap: 10px;
      padding: 8px 20px;
      border-bottom: 1px solid #E2E8F0;
      background: #fff;
      flex-shrink: 0;
    }
    .table-title { font-size: 12px; font-weight: 700; color: #0F172A; }
    .table-count { font-size: 11px; color: #94A3B8; }
    .table-wrap { flex: 1; overflow-y: auto; }
    .trade-table {
      width: 100%; border-collapse: collapse;
      font-size: 12px;
    }
    .trade-table thead { position: sticky; top: 0; z-index: 1; }
    .trade-table th {
      background: #F8FAFC; color: #94A3B8;
      font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.3px;
      padding: 7px 10px; text-align: left;
      border-bottom: 1px solid #E2E8F0;
      white-space: nowrap;
    }
    .trade-table td {
      padding: 7px 10px;
      border-bottom: 1px solid #F8FAFC;
      color: #0F172A;
      vertical-align: middle;
    }
    .trade-row { cursor: pointer; transition: background 0.1s; }
    .trade-row:hover { background: #F1F5F9; }
    .trade-row.selected-row { background: #EFF6FF; }
    .outcome-win { border-left: 2px solid #16A34A; }
    .outcome-loss { border-left: 2px solid #DC2626; }
    .outcome-open { border-left: 2px solid #2563EB; }
    .trade-code-link { color: #2563EB; font-weight: 600; font-size: 11px; }
    .strategy-tag {
      background: #F1F5F9; color: #475569;
      font-size: 9px; padding: 1px 5px; border-radius: 3px; font-weight: 600;
    }
    .dir-tag {
      font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px; margin-left: 3px;
    }
    .dir-sell { background: #FEE2E2; color: #DC2626; }
    .dir-buy  { background: #D1FAE5; color: #059669; }
    .bias-pill {
      display: inline-block;
      width: 14px; height: 14px; line-height: 14px; text-align: center;
      border-radius: 3px; font-size: 9px; font-weight: 800;
    }
    .bias-pill.bullish { background: #D1FAE5; color: #059669; }
    .bias-pill.bearish { background: #FEE2E2; color: #DC2626; }
    .bias-pill.neutral { background: #F1F5F9; color: #64748B; }
    .strength-tag { font-size: 9px; color: #94A3B8; margin-left: 2px; }
    .score-tag { font-size: 10px; color: #64748B; margin-left: 4px; font-variant-numeric: tabular-nums; }
    .mono { font-variant-numeric: tabular-nums; }
    .center { text-align: center; }
    .td-date { white-space: nowrap; }
    .td-code { white-space: nowrap; }
    .td-reason { font-size: 11px; color: #64748B; max-width: 140px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
    .pnl-win  { color: #16A34A; font-weight: 600; }
    .pnl-loss { color: #DC2626; font-weight: 600; }
    .flag-warn { color: #D97706; font-weight: 600; }
    .empty-cell { text-align: center; padding: 40px; font-size: 13px; color: #94A3B8; }
    /* Table footer */
    .table-footer { border-top: 1px solid #E2E8F0; padding: 10px 20px; background: #fff; flex-shrink: 0; }
    .loading-row { display: flex; justify-content: center; padding: 4px 0; }
    .dot-loader { display: flex; gap: 4px; align-items: center; }
    .dot-loader span {
      width: 6px; height: 6px; border-radius: 50%; background: #94A3B8;
      animation: bounce 0.8s ease-in-out infinite;
    }
    .dot-loader span:nth-child(2) { animation-delay: 0.15s; }
    .dot-loader span:nth-child(3) { animation-delay: 0.30s; }
    @keyframes bounce {
      0%, 100% { transform: translateY(0); opacity: 0.5; }
      50%       { transform: translateY(-4px); opacity: 1; }
    }
    .btn-load-more {
      background: none; border: 1px solid #E2E8F0; border-radius: 6px;
      padding: 6px 16px; font-size: 12px; color: #2563EB; cursor: pointer;
      width: 100%; text-align: center;
    }
    .btn-load-more:hover { background: #EFF6FF; border-color: #BFDBFE; }
  `],
})
export class AuditComponent implements OnInit {
  summary: PortfolioSummary | null = null;
  summaryError: string | null = null;

  trades: TradeListItem[] = [];
  totalCount: number | null = null;
  hasMore = false;
  tradesLoading = false;
  private page = 0;
  private readonly PAGE_SIZE = 20;

  signalQuality: SignalQuality | null = null;
  selectedTrade: TradeListItem | null = null;

  fromDate = '';
  toDate = '';

  constructor(private svc: AuditService) {}

  ngOnInit(): void {
    this.loadSummary();
    this.loadSignalQuality();
    this.loadTrades(true);
  }

  onFilterChange(): void {
    this.loadSummary();
    this.loadSignalQuality();
    this.loadTrades(true);
  }

  clearFilters(): void {
    this.fromDate = '';
    this.toDate = '';
    this.onFilterChange();
  }

  loadMore(): void { this.loadTrades(false); }

  private loadSummary(): void {
    this.summary = null;
    this.summaryError = null;
    this.svc.getSummary(this.fromDate || undefined, this.toDate || undefined).subscribe({
      next:  s  => { this.summary = s; },
      error: () => { this.summaryError = 'Could not load summary.'; },
    });
  }

  private loadSignalQuality(): void {
    this.svc.getSignalQuality(this.fromDate || undefined, this.toDate || undefined).subscribe({
      next: q => { this.signalQuality = q; },
    });
  }

  private loadTrades(reset: boolean): void {
    if (reset) {
      this.page = 0;
      this.trades = [];
      this.totalCount = null;
      this.hasMore = false;
    }
    this.tradesLoading = true;
    this.svc.getTrades(this.page, this.PAGE_SIZE, this.fromDate || undefined, this.toDate || undefined)
      .subscribe({
        next: (r: TradeListResponse) => {
          this.trades = [...this.trades, ...r.trades];
          this.totalCount = r.totalCount;
          this.hasMore = r.hasMore;
          this.page++;
          this.tradesLoading = false;
        },
        error: () => { this.tradesLoading = false; },
      });
  }

  openDrawer(t: TradeListItem): void  { this.selectedTrade = t; }
  closeDrawer(): void                  { this.selectedTrade = null; }

  // ── Formatters ────────────────────────────────────────────────────────────

  fmt1(v?: number | null): string { return v != null ? v.toFixed(1) : '—'; }
  fmt2(v?: number | null): string { return v != null ? v.toFixed(2) : '—'; }
  fmtCr(v: number): string {
    return Math.abs(v) >= 1_00_000
      ? (v / 1_00_000).toFixed(2) + 'L'
      : v.toLocaleString('en-IN', { maximumFractionDigits: 0 });
  }
  fmtPnl(v?: number | null): string {
    if (v == null) return '—';
    return (v >= 0 ? '+' : '') + '₹' + this.fmtCr(v);
  }
  fmtDate(s: string): string {
    return s ? s.substring(0, 10) : '—';
  }
  biasCss(b?: string): string { return `bias-pill ${(b ?? '').toLowerCase()}`; }
  barPct(count: number, total: number): number {
    return total > 0 ? Math.round((count / total) * 100) : 0;
  }
  abbreviate(strategy: string): string {
    const map: Record<string, string> = {
      BullPutSpread: 'BPS', BearCallSpread: 'BCS',
      BullCallSpread: 'BCS+', BearPutSpread: 'BPS-',
      IronCondor: 'IC', ShortStraddle: 'SS', ShortStrangle: 'SStg',
    };
    return map[strategy] ?? strategy;
  }

  strategyEntries(): [string, number][] {
    return Object.entries(this.summary?.strategyMix ?? {});
  }
  vixWinEntries(): [string, number][] {
    return Object.entries(this.summary?.winRateByVixRegime ?? {});
  }
  accByConfEntries(): [string, number][] {
    return Object.entries(this.signalQuality?.accuracyByConfidence ?? {});
  }
}
