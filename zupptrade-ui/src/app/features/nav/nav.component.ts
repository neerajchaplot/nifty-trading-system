import { Component, EventEmitter, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-nav',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule],
  template: `
    <nav class="nav">
      <img src="assets/zupp-icon.jpg" alt="ZuppTrade" class="nav-logo">
      <span class="nav-sub">Nifty 50 Options Dashboard</span>
      <div class="nav-right">
        <span class="nav-time">{{ clock }}</span>
        <span class="live-badge">
          <span class="live-dot"></span> LIVE
        </span>
        <button class="btn-icon" (click)="refresh.emit()">↻ Refresh</button>
      </div>
    </nav>
  `,
  styles: [`
    .nav {
      height: 52px;
      background: #fff;
      border-bottom: 1px solid #E2E8F0;
      display: flex;
      align-items: center;
      padding: 0 20px;
      flex-shrink: 0;
      z-index: 100;
    }
    .nav-logo {
      height: 36px;
      width: auto;
      margin-right: 12px;
      object-fit: contain;
    }
    .nav-sub {
      font-size: 12px;
      color: #94A3B8;
      font-weight: 400;
      padding-left: 12px;
      border-left: 1px solid #E2E8F0;
    }
    .nav-right {
      margin-left: auto;
      display: flex;
      align-items: center;
      gap: 16px;
    }
    .nav-time {
      font-size: 12px;
      color: #475569;
      font-variant-numeric: tabular-nums;
    }
    .live-badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 11px;
      font-weight: 600;
      color: #16A34A;
      background: #F0FDF4;
      border: 1px solid #BBF7D0;
      padding: 2px 8px;
      border-radius: 99px;
    }
    .live-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #16A34A;
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
    .btn-icon {
      background: none;
      border: 1px solid #E2E8F0;
      border-radius: 6px;
      padding: 5px 10px;
      font-size: 12px;
      color: #475569;
      cursor: pointer;
      font-family: inherit;
      display: flex;
      align-items: center;
      gap: 5px;
    }
    .btn-icon:hover { background: #F8FAFC; border-color: #CBD5E1; }
  `],
})
export class NavComponent implements OnInit, OnDestroy {
  @Output() refresh = new EventEmitter<void>();

  clock = '';
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.updateClock();
    this.timer = setInterval(() => this.updateClock(), 1000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private updateClock(): void {
    const now = new Date();
    const opts: Intl.DateTimeFormatOptions = {
      timeZone: 'Asia/Kolkata',
      hour12: false,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    };
    const parts = new Intl.DateTimeFormat('en-IN', opts).formatToParts(now);
    const p: Record<string, string> = {};
    parts.forEach(x => (p[x.type] = x.value));
    const months = ['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    this.clock = `${p['day']} ${months[parseInt(p['month'])]} ${p['year']}  ·  ${p['hour']}:${p['minute']}:${p['second']} IST`;
  }
}
