import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Agent1Signal } from '../../core/models/agent1-signal.model';
import { BiasPillComponent } from '../../shared/components/bias-pill/bias-pill.component';
import { ConfidencePillComponent } from '../../shared/components/confidence-pill/confidence-pill.component';

@Component({
  selector: 'app-market-strip',
  standalone: true,
  imports: [CommonModule, BiasPillComponent, ConfidencePillComponent],
  template: `
    <div class="strip-wrapper">
      <div class="strip" (click)="expanded = !expanded">
        <div class="strip-item">
          <span class="strip-label">Nifty 50</span>
          <span class="strip-value">
            {{ signal?.vixLevel != null ? '—' : '—' }}&nbsp;
          </span>
        </div>
        <div class="strip-item">
          <span class="strip-label">VIX</span>
          <span class="strip-value">
            {{ signal?.vixLevel | number:'1.2-2' }}&nbsp;
            <span class="strip-change" [class.down]="signal?.vixDirection === 'Falling'" [class.up]="signal?.vixDirection === 'Rising'">
              {{ signal?.vixDirection === 'Falling' ? '▼' : signal?.vixDirection === 'Rising' ? '▲' : '—' }}
              {{ signal?.vixRegime }}
            </span>
          </span>
        </div>
        <div class="strip-item">
          <span class="strip-label">Bias</span>
          <span class="strip-value">
            <app-bias-pill *ngIf="signal" [bias]="signal.bias" [strength]="signal.strength"></app-bias-pill>
            <span *ngIf="!signal" class="muted">—</span>
          </span>
        </div>
        <div class="strip-item">
          <span class="strip-label">Score</span>
          <span class="strip-score" [class.pos]="(signal?.compositeScore ?? 0) > 0" [class.neg]="(signal?.compositeScore ?? 0) < 0">
            {{ signal ? (signal.compositeScore > 0 ? '+' : '') + (signal.compositeScore | number:'1.3-3') : '—' }}
          </span>
        </div>
        <div class="strip-item">
          <span class="strip-label">Confidence</span>
          <span class="strip-value">
            <app-confidence-pill *ngIf="signal" [confidence]="signal.confidence"></app-confidence-pill>
            <span *ngIf="!signal" class="muted">—</span>
          </span>
        </div>
        <div class="strip-item">
          <span class="strip-label">Signal Age</span>
          <span class="strip-value" style="font-size:12px;">{{ signalAge }}</span>
        </div>
        <span class="strip-expand">{{ expanded ? '⌃' : '⌄' }}</span>
      </div>

      <div class="strip-expanded" *ngIf="expanded && signal && scoreBreakdown">
        <div class="tier-grid">
          <div class="tier-card" *ngFor="let tier of tierCards">
            <div class="tier-title">
              {{ tier.name }}
              <span class="tier-score-val">{{ tier.score > 0 ? '+' : '' }}{{ tier.score | number:'1.2-2' }}</span>
            </div>
            <div class="tier-row" *ngFor="let row of tier.rows">
              <span>{{ row.key }}</span>
              <span class="tier-row-val">{{ row.value }}</span>
            </div>
          </div>
        </div>
        <div class="strip-commentary" *ngIf="signal && signal.keyLevels">
          💬 {{ signal.keyLevels }}
        </div>
      </div>
    </div>
  `,
  styles: [`
    .strip-wrapper {
      flex-shrink: 0;
      background: #fff;
      border-bottom: 1px solid #E2E8F0;
    }
    .strip {
      height: 50px;
      display: flex;
      align-items: center;
      padding: 0 20px;
      cursor: pointer;
      user-select: none;
      gap: 0;
    }
    .strip:hover { background: #FAFBFC; }
    .strip-item {
      display: flex;
      flex-direction: column;
      padding: 0 18px;
      border-right: 1px solid #F1F5F9;
    }
    .strip-item:first-child { padding-left: 0; }
    .strip-label {
      font-size: 10px;
      font-weight: 600;
      color: #94A3B8;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .strip-value {
      font-size: 14px;
      font-weight: 700;
      color: #0F172A;
      font-variant-numeric: tabular-nums;
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .strip-change { font-size: 11px; font-weight: 600; }
    .up { color: #16A34A; }
    .down { color: #DC2626; }
    .strip-score {
      font-size: 13px;
      font-weight: 700;
      color: #2563EB;
    }
    .strip-score.pos { color: #16A34A; }
    .strip-score.neg { color: #DC2626; }
    .strip-expand {
      margin-left: auto;
      font-size: 18px;
      color: #94A3B8;
      line-height: 1;
    }
    .muted { color: #94A3B8; font-size: 12px; }
    .strip-expanded {
      padding: 12px 20px 16px;
      background: #fff;
      border-top: 1px solid #F1F5F9;
    }
    .tier-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 12px;
      margin-top: 8px;
    }
    .tier-card {
      background: #F8FAFC;
      border: 1px solid #E2E8F0;
      border-radius: 10px;
      padding: 12px 14px;
    }
    .tier-title {
      font-size: 11px;
      font-weight: 700;
      color: #475569;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-bottom: 8px;
      display: flex;
      justify-content: space-between;
    }
    .tier-score-val { color: #16A34A; font-weight: 700; }
    .tier-row {
      display: flex;
      justify-content: space-between;
      font-size: 12px;
      color: #475569;
      padding: 2px 0;
    }
    .tier-row-val { font-weight: 600; color: #0F172A; }
    .strip-commentary {
      margin-top: 10px;
      font-size: 12px;
      color: #475569;
      background: #EFF6FF;
      border: 1px solid #BFDBFE;
      border-radius: 6px;
      padding: 7px 12px;
    }
  `],
})
export class MarketStripComponent {
  @Input() signal: Agent1Signal | null = null;

  expanded = false;

  get signalAge(): string {
    if (!this.signal?.timestamp) return '—';
    const diffMs = Date.now() - new Date(this.signal.timestamp).getTime();
    const mins = Math.floor(diffMs / 60000);
    return mins < 1 ? 'just now' : `${mins} min ago`;
  }

  get scoreBreakdown(): Record<string, unknown> | null {
    if (!this.signal?.scoreBreakdown) return null;
    try { return JSON.parse(this.signal.scoreBreakdown); } catch { return null; }
  }

  get tierCards(): { name: string; score: number; rows: { key: string; value: string }[] }[] {
    const bd = this.scoreBreakdown;
    if (!bd || !this.signal) return [];
    return [
      {
        name: 'T1A · Price Structure', score: (bd['tier1aScore'] as number) ?? 0,
        rows: [
          { key: 'VIX Level', value: this.signal.vixLevel?.toFixed(2) ?? '—' },
          { key: 'VIX Regime', value: this.signal.vixRegime ?? '—' },
          { key: 'VIX Direction', value: this.signal.vixDirection ?? '—' },
        ],
      },
      {
        name: 'T1B · Technicals', score: (bd['tier1bScore'] as number) ?? 0,
        rows: [
          { key: 'Score', value: ((bd['tier1bScore'] as number) ?? 0).toFixed(3) },
        ],
      },
      {
        name: 'T2 · Institutional', score: (bd['tier2Score'] as number) ?? 0,
        rows: [
          { key: 'Score', value: ((bd['tier2Score'] as number) ?? 0).toFixed(3) },
        ],
      },
    ];
  }
}
