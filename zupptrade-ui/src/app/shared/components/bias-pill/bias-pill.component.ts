import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Bias, Strength } from '../../../core/models/enums';

@Component({
  selector: 'app-bias-pill',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="bias-pill" [ngClass]="cssClass">
      {{ icon }} {{ label }}
    </span>
  `,
  styles: [`
    .bias-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 8px;
      border-radius: 99px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.2px;
      white-space: nowrap;
    }
    .bias-bullish { background: #F0FDF4; color: #16A34A; border: 1px solid #BBF7D0; }
    .bias-bearish { background: #FEF2F2; color: #DC2626; border: 1px solid #FECACA; }
    .bias-neutral  { background: #FFFBEB; color: #D97706; border: 1px solid #FDE68A; }
  `],
})
export class BiasPillComponent {
  @Input() bias: Bias = 'NEUTRAL';
  @Input() strength: Strength | null = null;

  get cssClass(): string {
    return `bias-${this.bias.toLowerCase()}`;
  }

  get icon(): string {
    return this.bias === 'BULLISH' ? '▲' : this.bias === 'BEARISH' ? '▼' : '◆';
  }

  get label(): string {
    const s = this.strength ? ` · ${this.strength}` : '';
    return `${this.bias}${s}`;
  }
}
