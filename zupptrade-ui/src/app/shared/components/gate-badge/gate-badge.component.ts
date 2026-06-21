import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

type GateState = 'pass' | 'fail' | 'info';

@Component({
  selector: 'app-gate-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="gate-badge" [ngClass]="'gate-' + state">
      {{ icon }}
    </span>
  `,
  styles: [`
    .gate-badge {
      width: 22px;
      height: 22px;
      border-radius: 5px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 800;
      flex-shrink: 0;
    }
    .gate-pass { background: #F0FDF4; color: #16A34A; }
    .gate-fail { background: #FEF2F2; color: #DC2626; }
    .gate-info { background: #F8FAFC; color: #94A3B8; border: 1px solid #E2E8F0; }
  `],
})
export class GateBadgeComponent {
  @Input() state: GateState = 'info';

  get icon(): string {
    return this.state === 'pass' ? '✓' : this.state === 'fail' ? '✗' : '○';
  }
}
