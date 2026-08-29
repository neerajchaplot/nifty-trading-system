import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EMPTY } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuditService } from '../../core/services/audit.service';
import { Agent5Service } from '../../core/services/agent5.service';
import { DashboardStateService } from '../../core/services/dashboard-state.service';
import { PortfolioSummary } from '../../core/models/audit.models';
import { ActiveTrade, MarginUtilization } from '../../core/models/trade.model';

/** Trading → P&L segment: today / WTD / MTD realized roll-up + open P&L + capital utilization (Agent 5). */
@Component({
  selector: 'app-pnl',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="grid" *ngIf="activeTrades$ | async as trades">
      <div class="tile">
        <div class="t-label">Today closed</div>
        <div class="t-val" [class.pos]="closedToday >= 0" [class.neg]="closedToday < 0">₹{{ closedToday | number:'1.0-0' }}</div>
        <div class="t-sub">{{ today?.totalTrades ?? 0 }} closed</div>
      </div>
      <div class="tile">
        <div class="t-label">Open P&L</div>
        <div class="t-val" [class.pos]="openPnl(trades) >= 0" [class.neg]="openPnl(trades) < 0">₹{{ openPnl(trades) | number:'1.0-0' }}</div>
        <div class="t-sub">{{ trades.length }} active</div>
      </div>
      <div class="tile">
        <div class="t-label">Week to date</div>
        <div class="t-val" [class.pos]="wtdTotal(trades) >= 0" [class.neg]="wtdTotal(trades) < 0">₹{{ wtdTotal(trades) | number:'1.0-0' }}</div>
        <div class="t-sub">closed + open</div>
      </div>
      <div class="tile">
        <div class="t-label">Month to date</div>
        <div class="t-val" [class.pos]="mtdTotal(trades) >= 0" [class.neg]="mtdTotal(trades) < 0">₹{{ mtdTotal(trades) | number:'1.0-0' }}</div>
        <div class="t-sub">closed + open</div>
      </div>
    </div>

    <!-- Capital utilization (Agent 5 margin) -->
    <div class="cap-card" *ngIf="marginData as m; else capNote">
      <div class="cap-head">
        <span class="cap-title">Capital deployed</span>
        <span class="cap-pct" [style.color]="barColor">{{ m.utilizationPct | number:'1.0-0' }}%</span>
      </div>
      <div class="cap-track"><div class="cap-fill" [style.width.%]="clampPct" [style.background]="barColor"></div></div>
      <div class="cap-legend">
        <span>Used ₹{{ m.usedMargin | number:'1.0-0' }}</span>
        <span>Available ₹{{ m.availableMargin | number:'1.0-0' }}</span>
      </div>
    </div>
    <ng-template #capNote>
      <div class="cap-note">{{ marginError ? 'Capital utilization unavailable right now.' : 'Loading capital utilization…' }}</div>
    </ng-template>
  `,
  styles: [`
    .grid { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
    .tile { background:var(--ion-card-background,#fff); border:1px solid var(--zt-border); border-radius:12px; padding:12px; }
    .t-label { font-size:11px; color:var(--zt-sub); }
    .t-val { font-size:20px; font-weight:800; margin-top:2px; }
    .t-sub { font-size:10px; color:var(--zt-muted); margin-top:2px; }
    .pos { color:var(--zt-green); }
    .neg { color:var(--zt-red); }
    .cap-card { margin-top:16px; border:1px solid var(--zt-border); border-radius:12px; padding:12px; background:var(--ion-card-background,#fff); }
    .cap-head { display:flex; justify-content:space-between; align-items:baseline; }
    .cap-title { font-size:12px; color:var(--zt-sub); font-weight:600; }
    .cap-pct { font-size:16px; font-weight:800; }
    .cap-track { height:8px; border-radius:99px; background:var(--zt-border); overflow:hidden; margin:8px 0 6px; }
    .cap-fill { height:100%; border-radius:99px; transition:width .3s ease; }
    .cap-legend { display:flex; justify-content:space-between; font-size:11px; color:var(--zt-muted); }
    .cap-note { font-size:11px; color:var(--zt-muted); text-align:center; margin-top:14px; }
  `],
})
export class PnlComponent implements OnInit {
  private audit = inject(AuditService);
  private agent5 = inject(Agent5Service);
  private state = inject(DashboardStateService);

  activeTrades$ = this.state.activeTrades;
  today: PortfolioSummary | null = null;
  wtd: PortfolioSummary | null = null;
  mtd: PortfolioSummary | null = null;

  marginData: MarginUtilization | null = null;
  marginError = false;

  ngOnInit(): void {
    const t = this.iso(new Date());
    this.audit.getSummary(t, t).pipe(catchError(() => EMPTY)).subscribe(s => this.today = s);
    this.audit.getSummary(this.mondayIso(), t).pipe(catchError(() => EMPTY)).subscribe(s => this.wtd = s);
    this.audit.getSummary(this.firstOfMonthIso(), t).pipe(catchError(() => EMPTY)).subscribe(s => this.mtd = s);
    this.agent5.marginUtilization()
      .pipe(catchError(() => { this.marginError = true; return EMPTY; }))
      .subscribe(m => this.marginData = m);
  }

  get closedToday(): number { return this.today?.totalRealizedPnl ?? 0; }
  openPnl(trades: ActiveTrade[]): number { return trades.reduce((a, tr) => a + (tr.markToMarketPnl ?? 0), 0); }
  wtdTotal(trades: ActiveTrade[]): number { return (this.wtd?.totalRealizedPnl ?? 0) + this.openPnl(trades); }
  mtdTotal(trades: ActiveTrade[]): number { return (this.mtd?.totalRealizedPnl ?? 0) + this.openPnl(trades); }

  get utilizationPct(): number { return this.marginData?.utilizationPct ?? 0; }
  get clampPct(): number { return Math.max(0, Math.min(100, this.utilizationPct)); }
  get barColor(): string {
    const p = this.utilizationPct;
    if (p >= 85) return 'var(--zt-red)';
    if (p >= 60) return 'var(--zt-amber)';
    return 'var(--zt-green)';
  }

  private iso(d: Date): string { return d.toISOString().split('T')[0]; }
  private mondayIso(): string {
    const d = new Date(); const day = d.getDay(); const diff = (day === 0 ? 6 : day - 1);
    d.setDate(d.getDate() - diff); return this.iso(d);
  }
  private firstOfMonthIso(): string { const d = new Date(); d.setDate(1); return this.iso(d); }
}
