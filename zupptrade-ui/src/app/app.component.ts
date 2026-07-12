import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Observable } from 'rxjs';
import { Agent1Signal } from './core/models/agent1-signal.model';
import { ActiveTrade } from './core/models/trade.model';
import { DashboardStateService } from './core/services/dashboard-state.service';
import { AgentUserService } from './core/services/agent-user.service';
import { UserStateService } from './core/services/user-state.service';
import { NavComponent } from './features/nav/nav.component';
import { MarketStripComponent } from './features/market-strip/market-strip.component';
import { RecommendationComponent } from './features/recommendation/recommendation.component';
import { LiveMonitorComponent } from './features/live-monitor/live-monitor.component';
import { AuditComponent } from './features/audit/audit.component';
import { UserProfileComponent } from './features/user-profile/user-profile.component';

type TabId = 'trading' | 'audit' | 'profile';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    NavComponent,
    MarketStripComponent,
    RecommendationComponent,
    LiveMonitorComponent,
    AuditComponent,
    UserProfileComponent,
  ],
  template: `
    <div class="shell">
      <app-nav (refresh)="onRefresh()"></app-nav>

      <!-- Tab strip -->
      <div class="tab-strip">
        <button class="tab-btn" [class.tab-active]="activeTab === 'trading'" (click)="activeTab = 'trading'">
          Trading
        </button>
        <button class="tab-btn" [class.tab-active]="activeTab === 'audit'" (click)="activeTab = 'audit'">
          Audit
        </button>
        <button class="tab-btn" [class.tab-active]="activeTab === 'profile'" (click)="activeTab = 'profile'">
          Profile
        </button>
      </div>

      <!-- Trading view -->
      <ng-container *ngIf="activeTab === 'trading'">
        <app-market-strip [signal]="signal$ | async"></app-market-strip>
        <div class="body">
          <div class="left-zone">
            <app-recommendation [signal]="signal$ | async"></app-recommendation>
          </div>
          <div class="right-zone">
            <app-live-monitor [trades]="(trades$ | async) ?? []"></app-live-monitor>
          </div>
        </div>
      </ng-container>

      <!-- Audit view -->
      <app-audit *ngIf="activeTab === 'audit'" class="audit-fill"></app-audit>

      <!-- Profile view -->
      <app-user-profile *ngIf="activeTab === 'profile'" class="audit-fill"></app-user-profile>
    </div>
  `,
  styles: [`
    .shell {
      display: flex;
      flex-direction: column;
      height: 100vh;
      overflow: hidden;
      min-width: 1200px;
    }
    /* Tab strip */
    .tab-strip {
      display: flex;
      align-items: center;
      gap: 0;
      background: #fff;
      border-bottom: 1px solid #E2E8F0;
      padding: 0 20px;
      flex-shrink: 0;
    }
    .tab-btn {
      background: none; border: none;
      padding: 9px 18px;
      font-size: 12px; font-weight: 600; color: #94A3B8;
      cursor: pointer; border-bottom: 2px solid transparent;
      margin-bottom: -1px;
      transition: color 0.15s, border-color 0.15s;
    }
    .tab-btn:hover { color: #475569; }
    .tab-btn.tab-active { color: #2563EB; border-bottom-color: #2563EB; }
    /* Trading layout */
    .body {
      display: flex;
      flex: 1;
      overflow: hidden;
      background: #E3E8F0;
      padding: 12px;
      gap: 12px;
    }
    .left-zone {
      width: 42%;
      flex-shrink: 0;
      background: #FFFFFF;
      border-radius: 10px;
      border: 1px solid #D1D9E6;
      box-shadow: 0 1px 4px rgba(0,0,0,0.06);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .right-zone {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      background: #FFFFFF;
      border-radius: 10px;
      border: 1px solid #D1D9E6;
      box-shadow: 0 1px 4px rgba(0,0,0,0.06);
    }
    /* Audit fills remaining height */
    .audit-fill {
      flex: 1;
      overflow: hidden;
    }
  `],
})
export class AppComponent implements OnInit {
  signal$!: Observable<Agent1Signal | null>;
  trades$!: Observable<ActiveTrade[]>;
  activeTab: TabId = 'trading';

  constructor(
    private state: DashboardStateService,
    private agentUser: AgentUserService,
    private userState: UserStateService,
  ) {}

  ngOnInit(): void {
    this.signal$ = this.state.signal$;
    this.trades$ = this.state.activeTrades$;
    this.agentUser.me().subscribe({
      next: profile => this.userState.setProfile(profile),
      error: err => console.error('Failed to load user profile — recommend will be unavailable', err),
    });
  }

  onRefresh(): void {
    this.state.refreshSignal();
  }
}
