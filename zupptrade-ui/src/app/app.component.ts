import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Observable } from 'rxjs';
import { Agent1Signal } from './core/models/agent1-signal.model';
import { ActiveTrade } from './core/models/trade.model';
import { DashboardStateService } from './core/services/dashboard-state.service';
import { NavComponent } from './features/nav/nav.component';
import { MarketStripComponent } from './features/market-strip/market-strip.component';
import { RecommendationComponent } from './features/recommendation/recommendation.component';
import { LiveMonitorComponent } from './features/live-monitor/live-monitor.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    NavComponent,
    MarketStripComponent,
    RecommendationComponent,
    LiveMonitorComponent,
  ],
  template: `
    <div class="shell">
      <app-nav (refresh)="onRefresh()"></app-nav>
      <app-market-strip [signal]="signal$ | async"></app-market-strip>
      <div class="body">
        <div class="left-zone">
          <app-recommendation [signal]="signal$ | async"></app-recommendation>
        </div>
        <div class="right-zone">
          <app-live-monitor [trades]="(trades$ | async) ?? []"></app-live-monitor>
        </div>
      </div>
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
    .body {
      display: flex;
      flex: 1;
      overflow: hidden;
    }
    .left-zone {
      width: 42%;
      flex-shrink: 0;
      border-right: 1px solid #E2E8F0;
      background: #F8FAFC;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .right-zone {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      background: #F8FAFC;
    }
  `],
})
export class AppComponent implements OnInit {
  signal$!: Observable<Agent1Signal | null>;
  trades$!: Observable<ActiveTrade[]>;

  constructor(private state: DashboardStateService) {}

  ngOnInit(): void {
    this.signal$ = this.state.signal$;
    this.trades$ = this.state.activeTrades$;
  }

  onRefresh(): void {
    this.state.refreshSignal();
  }
}
