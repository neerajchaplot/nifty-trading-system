import { Component, Input, Output, EventEmitter, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuditService } from '../../core/services/audit.service';
import { TradeAudit, SignalChapter, RecommendationChapter, MonitoringEvent } from '../../core/models/audit.models';
import { TradeListItem } from '../../core/models/audit.models';

@Component({
  selector: 'app-trade-audit-drawer',
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Backdrop -->
    <div class="backdrop" (click)="close.emit()"></div>

    <!-- Drawer panel -->
    <div class="drawer">
      <!-- Header -->
      <div class="drawer-header">
        <div class="drawer-title">
          <span class="trade-code">{{ trade?.tradeCode }}</span>
          <span class="outcome-badge" [class]="outcomeCss(trade?.outcome)">
            {{ trade?.outcome }}
          </span>
        </div>
        <div class="drawer-meta">
          {{ trade?.strategy }} · Expiry {{ trade?.expiryDate }}
          <span *ngIf="trade?.actualPnl != null"
                class="pnl" [class.pnl-win]="(trade?.actualPnl ?? 0) > 0"
                [class.pnl-loss]="(trade?.actualPnl ?? 0) <= 0">
            {{ formatPnl(trade?.actualPnl) }}
          </span>
        </div>
        <button class="close-btn" (click)="close.emit()">✕</button>
      </div>

      <!-- Body -->
      <div class="drawer-body" *ngIf="!loading && !error && audit">

        <!-- ── Chapter 1: Signal ──────────────────────────────── -->
        <div class="chapter">
          <div class="chapter-head" (click)="toggle('signal')">
            <span class="chapter-num">1</span>
            <span class="chapter-title">Market Signal</span>
            <span class="chapter-tag">
              <span class="pill" [class]="biasCss(audit.signal?.bias)">{{ audit.signal?.bias }}</span>
              <span class="pill pill-neutral">{{ audit.signal?.strength }}</span>
            </span>
            <span class="chevron">{{ open.signal ? '▲' : '▼' }}</span>
          </div>
          <div class="chapter-body" *ngIf="open.signal">
            <div class="kv-grid">
              <div class="kv"><span class="k">Composite Score</span><span class="v">{{ fmt2(audit.signal?.compositeScore) }}</span></div>
              <div class="kv"><span class="k">Confidence</span><span class="v">{{ pct(audit.signal?.confidenceScore) }} · {{ audit.signal?.confidenceLabel }}</span></div>
              <div class="kv"><span class="k">VIX</span><span class="v">{{ fmt2(audit.signal?.vixLevel) }}</span></div>
              <div class="kv"><span class="k">VIX Regime</span><span class="v">{{ audit.signal?.vixRegime }}</span></div>
              <div class="kv"><span class="k">VIX Direction</span><span class="v">{{ audit.signal?.vixDirection }}</span></div>
              <div class="kv"><span class="k">Commentary Divergence</span>
                <span class="v" [class.flag-warn]="audit.signal?.commentaryDivergence">
                  {{ audit.signal?.commentaryDivergence ? 'YES ⚠' : 'No' }}
                </span>
              </div>
            </div>

            <div class="sub-section" *ngIf="audit.signal?.tierScores && objectKeys(audit.signal.tierScores).length">
              <div class="sub-label">Tier Scores</div>
              <div class="tier-rows">
                <div class="tier-row" *ngFor="let entry of tierEntries(audit.signal)">
                  <span class="tier-name">{{ entry[0] }}</span>
                  <div class="tier-bar-bg">
                    <div class="tier-bar-fill" [style.width.%]="tierBarPct(entry[1])"></div>
                  </div>
                  <span class="tier-val">{{ fmt3(entry[1]) }}</span>
                </div>
              </div>
            </div>

            <div class="sub-section" *ngIf="audit.signal?.dataGaps?.length">
              <div class="sub-label">Data Gaps</div>
              <div class="gap-list">
                <span class="gap-tag" *ngFor="let g of audit.signal.dataGaps">{{ g }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- ── Chapter 2: Recommendation ────────────────────── -->
        <div class="chapter">
          <div class="chapter-head" (click)="toggle('rec')">
            <span class="chapter-num">2</span>
            <span class="chapter-title">Recommendation</span>
            <span class="chapter-tag">{{ audit.recommendation?.strategy }}</span>
            <span class="chevron">{{ open.rec ? '▲' : '▼' }}</span>
          </div>
          <div class="chapter-body" *ngIf="open.rec">
            <!-- Legs table -->
            <table class="mini-table" *ngIf="audit.recommendation?.legs?.length">
              <thead>
                <tr><th>Action</th><th>Strike</th><th>Type</th><th>LTP</th><th>IV%</th><th>Δ</th><th>Θ</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let leg of audit.recommendation.legs"
                    [class.sell-row]="leg.action === 'SELL'"
                    [class.buy-row]="leg.action === 'BUY'">
                  <td><span class="leg-action" [class]="leg.action === 'SELL' ? 'sell' : 'buy'">{{ leg.action }}</span></td>
                  <td class="mono">{{ leg.strike | number:'1.0-0' }}</td>
                  <td>{{ leg.type }}</td>
                  <td class="mono">{{ fmt2(leg.ltp) }}</td>
                  <td class="mono">{{ fmt1(leg.iv) }}%</td>
                  <td class="mono">{{ fmt3(leg.delta) }}</td>
                  <td class="mono">{{ fmt1(leg.theta) }}</td>
                </tr>
              </tbody>
            </table>

            <div class="kv-grid" style="margin-top:10px">
              <div class="kv"><span class="k">Net Premium/Unit</span><span class="v mono">₹{{ fmt2(audit.recommendation?.netPremiumPerUnit) }}</span></div>
              <div class="kv"><span class="k">Lots × Lot Size</span><span class="v mono">{{ audit.recommendation?.lots }} × {{ audit.recommendation?.lotSize }}</span></div>
              <div class="kv"><span class="k">Max Profit</span><span class="v mono pnl-win">₹{{ fmtCr(audit.recommendation?.maxProfitTotal) }}</span></div>
              <div class="kv"><span class="k">Real Expected Loss</span><span class="v mono pnl-loss">₹{{ fmtCr(audit.recommendation?.realExpectedLossTotal) }}</span></div>
              <div class="kv"><span class="k">PoP / PoPP</span><span class="v mono">{{ fmt1(audit.recommendation?.pop) }}% / {{ fmt1(audit.recommendation?.popp) }}%</span></div>
              <div class="kv"><span class="k">RoC Theoretical</span><span class="v mono">{{ fmt2(audit.recommendation?.rocTheoreticalPct) }}%</span></div>
            </div>

            <!-- Gates -->
            <div class="sub-section" *ngIf="audit.recommendation?.gateResults">
              <div class="sub-label">Gate Results</div>
              <div class="gate-list">
                <div class="gate-row" *ngFor="let g of gateEntries(audit.recommendation)">
                  <span class="gate-badge" [class.gate-pass]="g[1]==='PASS'" [class.gate-fail]="g[1]==='FAIL'" [class.gate-ind]="g[1]==='INDICATIVE'">
                    {{ g[1] }}
                  </span>
                  <span class="gate-label">{{ g[0] }}</span>
                </div>
              </div>
            </div>

            <!-- Thresholds -->
            <div class="sub-section">
              <div class="sub-label">Thresholds</div>
              <div class="kv-grid">
                <div class="kv"><span class="k">T1 Watch</span><span class="v mono">{{ fmtNifty(audit.recommendation?.t1WatchNifty) }}</span></div>
                <div class="kv"><span class="k">T2 Readjust</span><span class="v mono">{{ fmtNifty(audit.recommendation?.t2ReadjustNifty) }}</span></div>
                <div class="kv"><span class="k">T2 Loss Trigger</span><span class="v mono pnl-loss">₹{{ fmtCr(audit.recommendation?.t2ReadjustPnlLoss) }}</span></div>
                <div class="kv"><span class="k">T3 Exit</span><span class="v mono">{{ fmtNifty(audit.recommendation?.t3ExitNifty) }}</span></div>
                <div class="kv"><span class="k">T3 Loss Trigger</span><span class="v mono pnl-loss">₹{{ fmtCr(audit.recommendation?.t3ExitPnlLoss) }}</span></div>
              </div>
            </div>
          </div>
        </div>

        <!-- ── Chapter 3: Execution ──────────────────────────── -->
        <div class="chapter">
          <div class="chapter-head" (click)="toggle('exec')">
            <span class="chapter-num">3</span>
            <span class="chapter-title">Execution</span>
            <span class="chapter-tag" *ngIf="audit.execution?.entry">
              Slippage: {{ fmt2(audit.execution.totalSlippage) }}
            </span>
            <span class="chapter-tag" *ngIf="!audit.execution?.entry">No fills recorded</span>
            <span class="chevron">{{ open.exec ? '▲' : '▼' }}</span>
          </div>
          <div class="chapter-body" *ngIf="open.exec">
            <ng-container *ngIf="audit.execution?.entry; else noExec">
              <div class="exec-block">
                <div class="exec-head">Entry</div>
                <div class="kv-grid">
                  <div class="kv"><span class="k">Executed At</span><span class="v mono">{{ fmtDt(audit.execution.entry!.executedAt) }}</span></div>
                  <div class="kv"><span class="k">Requested Net Premium</span><span class="v mono">₹{{ fmt2(audit.execution.entry!.requestedNetPremium) }}</span></div>
                  <div class="kv"><span class="k">Actual Net Premium</span><span class="v mono">₹{{ fmt2(audit.execution.entry!.actualNetPremium) }}</span></div>
                  <div class="kv"><span class="k">Slippage</span>
                    <span class="v mono" [class.flag-warn]="(audit.execution.entry!.slippagePct ?? 0) > 5">
                      {{ fmt2(audit.execution.entry!.slippagePct) }}%
                    </span>
                  </div>
                  <div class="kv"><span class="k">Filled Lots</span><span class="v mono">{{ audit.execution.entry!.filledLots }}</span></div>
                  <div class="kv"><span class="k">Status</span><span class="v">{{ audit.execution.entry!.brokerStatus }}</span></div>
                </div>
              </div>
              <div class="exec-block" *ngIf="audit.execution?.exit">
                <div class="exec-head">Exit</div>
                <div class="kv-grid">
                  <div class="kv"><span class="k">Executed At</span><span class="v mono">{{ fmtDt(audit.execution.exit!.executedAt) }}</span></div>
                  <div class="kv"><span class="k">Exit Net Premium</span><span class="v mono">₹{{ fmt2(audit.execution.exit!.actualNetPremium) }}</span></div>
                  <div class="kv"><span class="k">Status</span><span class="v">{{ audit.execution.exit!.brokerStatus }}</span></div>
                </div>
              </div>
            </ng-container>
            <ng-template #noExec>
              <div class="empty-state">No execution fills recorded for this trade.</div>
            </ng-template>
          </div>
        </div>

        <!-- ── Chapter 4: Monitoring ─────────────────────────── -->
        <div class="chapter">
          <div class="chapter-head" (click)="toggle('mon')">
            <span class="chapter-num">4</span>
            <span class="chapter-title">Monitoring Timeline</span>
            <span class="chapter-tag">
              {{ audit.monitoring?.events?.length ?? 0 }} events
              <ng-container *ngIf="(audit.monitoring?.readjustCount ?? 0) > 0">
                · {{ audit.monitoring?.readjustCount }} adjustments
              </ng-container>
            </span>
            <span class="chevron">{{ open.mon ? '▲' : '▼' }}</span>
          </div>
          <div class="chapter-body" *ngIf="open.mon">
            <div class="mon-stat-row">
              <span class="mon-stat"><span class="ms-val">{{ audit.monitoring?.holdCount }}</span><span class="ms-lbl">HOLD</span></span>
              <span class="mon-stat"><span class="ms-val warn">{{ audit.monitoring?.watchCount }}</span><span class="ms-lbl">WATCH</span></span>
              <span class="mon-stat"><span class="ms-val danger">{{ audit.monitoring?.readjustCount }}</span><span class="ms-lbl">READJUST</span></span>
              <span class="mon-stat" *ngIf="audit.monitoring?.exitTriggeredBy">
                <span class="ms-val">{{ audit.monitoring?.exitTriggeredBy }}</span>
                <span class="ms-lbl">EXIT TRIGGER</span>
              </span>
            </div>

            <div class="mon-events" *ngIf="audit.monitoring?.events?.length">
              <div class="mon-row mon-head">
                <span>Time</span><span>Action</span><span>Spot</span><span>MtM P&L</span><span>Threshold</span>
              </div>
              <div class="mon-row" *ngFor="let ev of audit.monitoring.events" [class]="monRowCss(ev)">
                <span class="mono">{{ fmtTime(ev.evaluatedAt) }}</span>
                <span><span class="action-badge" [class]="actionCss(ev.action)">{{ ev.action }}</span></span>
                <span class="mono">{{ ev.spotPrice != null ? (ev.spotPrice | number:'1.0-0') : '—' }}</span>
                <span class="mono" [class.pnl-win]="(ev.markToMarketPnl ?? 0) > 0" [class.pnl-loss]="(ev.markToMarketPnl ?? 0) < 0">
                  {{ ev.markToMarketPnl != null ? ('₹' + fmtCr(ev.markToMarketPnl)) : '—' }}
                </span>
                <span>{{ ev.thresholdHit ?? '—' }}</span>
              </div>
            </div>
            <div class="empty-state" *ngIf="!audit.monitoring?.events?.length">
              No monitoring evaluations recorded.
            </div>
          </div>
        </div>

      </div>

      <!-- Loading / error states -->
      <div class="drawer-body center" *ngIf="loading">
        <div class="spinner"></div><div class="state-msg">Loading audit…</div>
      </div>
      <div class="drawer-body center" *ngIf="error && !loading">
        <div class="state-msg err">{{ error }}</div>
      </div>
    </div>
  `,
  styles: [`
    .backdrop {
      position: fixed; inset: 0;
      background: rgba(15,23,42,0.25);
      z-index: 200;
    }
    .drawer {
      position: fixed; top: 0; right: 0; bottom: 0;
      width: 500px;
      background: #fff;
      border-left: 1px solid #E2E8F0;
      z-index: 201;
      display: flex; flex-direction: column;
      box-shadow: -4px 0 24px rgba(15,23,42,0.08);
      overflow: hidden;
    }
    /* Header */
    .drawer-header {
      padding: 14px 16px 12px;
      border-bottom: 1px solid #E2E8F0;
      flex-shrink: 0;
      position: relative;
    }
    .drawer-title { display: flex; align-items: center; gap: 8px; margin-bottom: 3px; }
    .trade-code { font-size: 14px; font-weight: 700; color: #0F172A; font-variant-numeric: tabular-nums; }
    .drawer-meta { font-size: 12px; color: #64748B; display: flex; gap: 10px; align-items: center; }
    .close-btn {
      position: absolute; top: 14px; right: 14px;
      background: none; border: none; font-size: 14px; color: #94A3B8;
      cursor: pointer; padding: 2px 5px; border-radius: 4px;
    }
    .close-btn:hover { background: #F1F5F9; color: #475569; }
    /* Body */
    .drawer-body { flex: 1; overflow-y: auto; }
    .drawer-body.center { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; }
    /* Chapters */
    .chapter { border-bottom: 1px solid #F1F5F9; }
    .chapter-head {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 16px; cursor: pointer;
      font-size: 12px; user-select: none;
    }
    .chapter-head:hover { background: #F8FAFC; }
    .chapter-num {
      width: 20px; height: 20px; border-radius: 50%;
      background: #EFF6FF; color: #2563EB;
      font-size: 10px; font-weight: 700;
      display: flex; align-items: center; justify-content: center;
      flex-shrink: 0;
    }
    .chapter-title { font-size: 12px; font-weight: 600; color: #0F172A; }
    .chapter-tag { margin-left: auto; font-size: 11px; color: #64748B; display: flex; gap: 4px; align-items: center; }
    .chevron { font-size: 9px; color: #94A3B8; margin-left: 4px; }
    .chapter-body { padding: 0 16px 14px; }
    /* KV grid */
    .kv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
    .kv { display: flex; flex-direction: column; gap: 1px; }
    .k { font-size: 10px; color: #94A3B8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.4px; }
    .v { font-size: 12px; color: #0F172A; font-weight: 500; }
    .mono { font-variant-numeric: tabular-nums; }
    /* Sub sections */
    .sub-section { margin-top: 10px; }
    .sub-label { font-size: 10px; color: #94A3B8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 5px; }
    /* Tier bars */
    .tier-rows { display: flex; flex-direction: column; gap: 4px; }
    .tier-row { display: flex; align-items: center; gap: 6px; font-size: 11px; }
    .tier-name { width: 160px; color: #475569; flex-shrink: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
    .tier-bar-bg { flex: 1; height: 5px; background: #F1F5F9; border-radius: 3px; overflow: hidden; }
    .tier-bar-fill { height: 100%; background: #2563EB; border-radius: 3px; transition: width 0.3s; }
    .tier-val { width: 36px; text-align: right; color: #475569; font-size: 10px; }
    /* Data gaps */
    .gap-list { display: flex; flex-wrap: wrap; gap: 4px; }
    .gap-tag { background: #FFF7ED; border: 1px solid #FED7AA; color: #C2410C; border-radius: 4px; padding: 2px 6px; font-size: 10px; }
    /* Mini table */
    .mini-table { width: 100%; border-collapse: collapse; font-size: 11px; margin-top: 4px; }
    .mini-table th { background: #F8FAFC; color: #94A3B8; font-size: 10px; text-transform: uppercase; letter-spacing: 0.3px; padding: 4px 6px; text-align: left; border-bottom: 1px solid #E2E8F0; }
    .mini-table td { padding: 4px 6px; border-bottom: 1px solid #F1F5F9; color: #0F172A; }
    .sell-row td { background: #FFF5F5; }
    .buy-row td { background: #F0FDF4; }
    .leg-action { font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px; }
    .leg-action.sell { background: #FEE2E2; color: #DC2626; }
    .leg-action.buy  { background: #D1FAE5; color: #059669; }
    /* Gates */
    .gate-list { display: flex; flex-direction: column; gap: 4px; }
    .gate-row { display: flex; align-items: center; gap: 8px; font-size: 11px; }
    .gate-badge { font-size: 9px; font-weight: 700; padding: 1px 6px; border-radius: 3px; min-width: 68px; text-align: center; }
    .gate-pass { background: #D1FAE5; color: #059669; }
    .gate-fail { background: #FEE2E2; color: #DC2626; }
    .gate-ind  { background: #FEF3C7; color: #D97706; }
    .gate-label { color: #475569; }
    /* Execution blocks */
    .exec-block { margin-bottom: 12px; }
    .exec-head { font-size: 10px; font-weight: 700; color: #2563EB; text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 6px; }
    /* Monitoring */
    .mon-stat-row { display: flex; gap: 16px; margin-bottom: 10px; }
    .mon-stat { display: flex; flex-direction: column; align-items: center; }
    .ms-val { font-size: 18px; font-weight: 800; color: #0F172A; }
    .ms-val.warn { color: #D97706; }
    .ms-val.danger { color: #DC2626; }
    .ms-lbl { font-size: 9px; color: #94A3B8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.3px; }
    .mon-events { border: 1px solid #E2E8F0; border-radius: 6px; overflow: hidden; }
    .mon-row { display: grid; grid-template-columns: 100px 80px 80px 90px 1fr; font-size: 11px; padding: 5px 8px; border-bottom: 1px solid #F1F5F9; gap: 4px; align-items: center; }
    .mon-head { background: #F8FAFC; color: #94A3B8; font-size: 10px; font-weight: 600; text-transform: uppercase; }
    .mon-row.readjust { background: #FFF7ED; }
    .mon-row.exit { background: #FFF1F2; }
    .action-badge { font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px; }
    .action-badge.HOLD      { background: #F1F5F9; color: #64748B; }
    .action-badge.WATCH     { background: #FEF3C7; color: #D97706; }
    .action-badge.READJUST  { background: #FFEDD5; color: #C2410C; }
    .action-badge.EXIT      { background: #FEE2E2; color: #DC2626; }
    /* Pills */
    .pill { font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px; text-transform: uppercase; }
    .pill.bullish { background: #D1FAE5; color: #059669; }
    .pill.bearish { background: #FEE2E2; color: #DC2626; }
    .pill.neutral { background: #F1F5F9; color: #64748B; }
    .pill-neutral { background: #F1F5F9; color: #64748B; }
    /* Outcome badge */
    .outcome-badge { font-size: 10px; font-weight: 700; padding: 2px 7px; border-radius: 99px; }
    .outcome-WIN  { background: #D1FAE5; color: #059669; }
    .outcome-LOSS { background: #FEE2E2; color: #DC2626; }
    .outcome-OPEN { background: #DBEAFE; color: #2563EB; }
    /* P&L */
    .pnl { font-size: 13px; font-weight: 700; }
    .pnl-win  { color: #16A34A; }
    .pnl-loss { color: #DC2626; }
    .flag-warn { color: #D97706; }
    /* States */
    .state-msg { font-size: 13px; color: #94A3B8; }
    .state-msg.err { color: #DC2626; }
    .empty-state { font-size: 12px; color: #94A3B8; padding: 8px 0; }
    .spinner {
      width: 24px; height: 24px; border-radius: 50%;
      border: 2px solid #E2E8F0; border-top-color: #2563EB;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
  `],
})
export class TradeAuditDrawerComponent implements OnChanges {
  @Input() trade: TradeListItem | null = null;
  @Output() close = new EventEmitter<void>();

  audit: TradeAudit | null = null;
  loading = false;
  error: string | null = null;

  open = { signal: true, rec: true, exec: false, mon: false };

  constructor(private svc: AuditService) {}

  ngOnChanges(): void {
    if (!this.trade) return;
    this.audit = null;
    this.error = null;
    this.loading = true;
    this.open = { signal: true, rec: true, exec: false, mon: false };
    this.svc.getAudit(this.trade.tradeId).subscribe({
      next: r  => { this.audit = r; this.loading = false; },
      error: () => { this.error = 'Failed to load audit data.'; this.loading = false; },
    });
  }

  toggle(k: 'signal' | 'rec' | 'exec' | 'mon'): void { this.open[k] = !this.open[k]; }

  // ── Formatters ────────────────────────────────────────────────────────────

  outcomeCss(o?: string): string { return `outcome-badge outcome-${o}`; }
  biasCss(b?: string):    string { return `pill ${(b ?? '').toLowerCase()}`; }
  actionCss(a: string):   string { return `action-badge ${a}`; }
  monRowCss(ev: MonitoringEvent): string {
    if (ev.action === 'EXIT')     return 'exit';
    if (ev.action === 'READJUST') return 'readjust';
    return '';
  }

  fmt1(v?: number | null): string { return v != null ? v.toFixed(1) : '—'; }
  fmt2(v?: number | null): string { return v != null ? v.toFixed(2) : '—'; }
  fmt3(v?: number | null): string { return v != null ? v.toFixed(3) : '—'; }
  pct(v?: number | null):  string { return v != null ? (v * 100).toFixed(0) + '%' : '—'; }
  fmtCr(v?: number | null): string {
    if (v == null) return '—';
    return Math.abs(v) >= 1_00_000
      ? (v / 1_00_000).toFixed(2) + 'L'
      : v.toLocaleString('en-IN', { maximumFractionDigits: 0 });
  }
  fmtNifty(v?: number | null): string { return v != null ? v.toLocaleString('en-IN') : '—'; }
  fmtPnl(v?: number | null):   string { return v != null ? '₹' + this.fmtCr(v) : '—'; }
  formatPnl(v?: number | null): string {
    if (v == null) return '';
    return (v >= 0 ? '+' : '') + '₹' + this.fmtCr(v);
  }
  fmtDt(s?: string | null): string {
    if (!s) return '—';
    return new Date(s).toLocaleString('en-IN', { dateStyle: 'short', timeStyle: 'short' });
  }
  fmtTime(s: string): string {
    return new Date(s).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
  }
  tierBarPct(v: number): number { return Math.min(100, Math.max(0, ((v + 0.3) / 0.6) * 100)); }

  objectKeys(obj: object): string[] { return Object.keys(obj); }
  tierEntries(ch: SignalChapter): [string, number][] {
    return Object.entries(ch.tierScores ?? {}) as [string, number][];
  }
  gateEntries(ch: RecommendationChapter): [string, string][] {
    return Object.entries(ch.gateResults ?? {}) as [string, string][];
  }
}
