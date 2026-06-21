import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-metric-box',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="metric-box">
      <div class="metric-label">{{ label }}</div>
      <div class="metric-val" [style.color]="color || null">{{ value }}</div>
      <div class="metric-sub" *ngIf="sub">{{ sub }}</div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .metric-box {
      background: #F8FAFC;
      border: 1px solid #E2E8F0;
      border-radius: 8px;
      padding: 10px 12px;
      text-align: center;
    }
    .metric-label {
      font-size: 10px;
      color: #94A3B8;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.4px;
      margin-bottom: 3px;
    }
    .metric-val {
      font-size: 15px;
      font-weight: 800;
      color: #0F172A;
    }
    .metric-sub {
      font-size: 10px;
      color: #94A3B8;
      margin-top: 1px;
    }
  `],
})
export class MetricBoxComponent {
  @Input() label = '';
  @Input() value = '';
  @Input() sub: string | null = null;
  @Input() color: string | null = null;
}
