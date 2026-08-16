import { Component, EventEmitter, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/services/auth.service';
import { UserStateService } from '../../core/services/user-state.service';
import { HelpModalComponent } from '../../shared/components/help-modal/help-modal.component';

@Component({
  selector: 'app-nav',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, HelpModalComponent],
  template: `
    <nav class="nav">
      <img src="assets/wordmark-transparent.png" alt="ZuppTrade" class="nav-logo">
      <span class="nav-sub">Nifty 50 Options Dashboard</span>
      <div class="nav-right">
        <span class="nav-time">{{ clock }}</span>
        <span class="live-badge">
          <span class="live-dot"></span> LIVE
        </span>
        <button class="btn-help" (click)="helpOpen = true" title="How ZuppTrade works" aria-label="Help">?</button>
        <button class="btn-icon" (click)="refresh.emit()">↻ Refresh</button>
        <div class="user-box" *ngIf="userName">
          <span class="user-line">
            <span class="user-ic" aria-hidden="true">👤</span>
            <span class="user-name" [title]="userName">{{ userName }}</span>
            <span class="user-mode" [class.mode-sim]="isSimulation">{{ modeLabel }}</span>
          </span>
          <button class="btn-icon logout-btn" (click)="logout()">⇥ Logout</button>
        </div>
        <button class="btn-icon" *ngIf="!userName" (click)="logout()">⇥ Logout</button>
      </div>
    </nav>
    <app-help-modal [open]="helpOpen" (closed)="helpOpen = false"></app-help-modal>
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

    .btn-help {
      width: 24px; height: 24px;
      border-radius: 50%;
      border: 1px solid #E2E8F0;
      background: #fff;
      color: #64748B;
      font-size: 13px; font-weight: 700;
      cursor: pointer;
      display: inline-flex; align-items: center; justify-content: center;
      font-family: inherit;
    }
    .btn-help:hover { background: #EFF6FF; border-color: #93C5FD; color: #2563EB; }

    /* Signed-in user: name on top, Logout beneath it */
    .user-box {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 3px;
      padding-left: 14px;
      border-left: 1px solid #E2E8F0;
    }
    .user-line {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      line-height: 1;
    }
    .user-ic { font-size: 12px; opacity: .75; }
    .user-name {
      font-size: 12px;
      font-weight: 600;
      color: #334155;
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .user-mode {
      font-size: 9px;
      font-weight: 700;
      letter-spacing: .04em;
      padding: 1px 6px;
      border-radius: 99px;
      background: #F0FDF4;
      color: #16A34A;
      border: 1px solid #BBF7D0;
    }
    .user-mode.mode-sim {
      background: #FEF3C7;
      color: #92400E;
      border-color: #FDE68A;
    }
    .logout-btn { padding: 3px 10px; }
  `],
})
export class NavComponent implements OnInit, OnDestroy {
  @Output() refresh = new EventEmitter<void>();

  helpOpen = false;
  clock = '';
  private timer?: ReturnType<typeof setInterval>;

  constructor(
    private auth: AuthService,
    private router: Router,
    private userState: UserStateService,
  ) {}

  /** Friendly name for the signed-in user: display name → email → user id. */
  get userName(): string | null {
    const p = this.userState.profile;
    if (!p) return null;
    return p.displayName || p.email || p.userId || null;
  }

  get isSimulation(): boolean {
    return this.userState.profile?.accountMode === 'SIMULATION';
  }

  get modeLabel(): string {
    return this.isSimulation ? 'SIM' : 'LIVE';
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }

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
