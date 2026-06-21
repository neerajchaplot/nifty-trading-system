import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

type BarVariant = 'safe' | 'caution' | 'danger';

@Component({
  selector: 'app-threshold-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bar-wrap">
      <div class="bar-fill" [ngClass]="'bar-' + variant" [style.width.%]="clampedProgress"></div>
    </div>
  `,
  styles: [`
    .bar-wrap {
      height: 6px;
      background: #F1F5F9;
      border-radius: 99px;
      overflow: hidden;
      flex: 1;
    }
    .bar-fill {
      height: 100%;
      border-radius: 99px;
      transition: width .3s;
    }
    .bar-safe    { background: linear-gradient(90deg, #4ADE80, #22C55E); }
    .bar-caution { background: linear-gradient(90deg, #FCD34D, #F59E0B); }
    .bar-danger  { background: linear-gradient(90deg, #FCA5A5, #EF4444); }
  `],
})
export class ThresholdBarComponent {
  /** 0–100 percentage of how filled the bar is */
  @Input() progress = 0;
  @Input() variant: BarVariant = 'safe';

  get clampedProgress(): number {
    return Math.min(100, Math.max(0, this.progress));
  }
}
