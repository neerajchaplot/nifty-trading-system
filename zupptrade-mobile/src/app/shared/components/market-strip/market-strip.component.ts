import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DashboardStateService } from '../../../core/services/dashboard-state.service';

/** Compact market context bar (NIFTY / VIX / bias / score) — pinned on the Trading screen. */
@Component({
  selector: 'app-market-strip',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="strip" *ngIf="signal$ | async as s; else nostrip">
      <span *ngIf="s.spot != null"><span class="k">NIFTY</span> <b>{{ s.spot | number:'1.0-0' }}</b></span>
      <span *ngIf="s.vixLevel != null"><span class="k">VIX</span> <b>{{ s.vixLevel | number:'1.1-1' }}</b></span>
      <span class="bias" [class.b-bull]="s.bias === 'BULLISH'" [class.b-bear]="s.bias === 'BEARISH'">
        {{ s.bias | titlecase }} · {{ s.strength | titlecase }}
      </span>
      <span><span class="k">Score</span> <b>{{ s.compositeScore | number:'1.2-2' }}</b></span>
      <span><span class="k">Conf</span> <b>{{ s.confidence }}</b></span>
    </div>
    <ng-template #nostrip>
      <div class="strip strip-empty">Generate a signal to see NIFTY · VIX · bias.</div>
    </ng-template>
  `,
  styles: [`
    .strip { display:flex; flex-wrap:wrap; gap:6px 14px; align-items:center; padding:8px 4px 4px; font-size:11px; color:var(--zt-text); }
    .k { color:var(--zt-muted); text-transform:uppercase; letter-spacing:.3px; }
    .strip b { font-weight:700; }
    .bias { font-weight:700; color:var(--zt-sub); }
    .b-bull { color:var(--zt-green); }
    .b-bear { color:var(--zt-red); }
    .strip-empty { color:var(--zt-muted); font-size:11px; }
  `],
})
export class MarketStripComponent {
  private state = inject(DashboardStateService);
  signal$ = this.state.signal;
}
