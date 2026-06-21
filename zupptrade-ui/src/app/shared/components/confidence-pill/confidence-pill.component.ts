import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Confidence } from '../../../core/models/enums';

@Component({
  selector: 'app-confidence-pill',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="conf-pill" [ngClass]="cssClass">{{ confidence }}</span>
  `,
  styles: [`
    .conf-pill {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 99px;
      font-size: 11px;
      font-weight: 600;
      white-space: nowrap;
    }
    .conf-high   { background: #F0FDF4; color: #16A34A; border: 1px solid #BBF7D0; }
    .conf-medium { background: #EFF6FF; color: #2563EB; border: 1px solid #BFDBFE; }
    .conf-low    { background: #FFFBEB; color: #D97706; border: 1px solid #FDE68A; }
  `],
})
export class ConfidencePillComponent {
  @Input() confidence: Confidence = 'LOW';

  get cssClass(): string {
    return `conf-${this.confidence.toLowerCase()}`;
  }
}
