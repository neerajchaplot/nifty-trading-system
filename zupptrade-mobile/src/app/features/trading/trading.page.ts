import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  IonContent, IonSegment, IonSegmentButton, IonLabel, IonRefresher, IonRefresherContent,
} from '@ionic/angular/standalone';
import { AppHeaderComponent } from '../../shared/components/app-header/app-header.component';
import { MarketStripComponent } from '../../shared/components/market-strip/market-strip.component';
import { ConnectionBannerComponent } from '../../shared/components/connection-banner/connection-banner.component';
import { RecommendComponent } from './recommend.component';
import { LiveTradesComponent } from './live-trades.component';
import { PnlComponent } from './pnl.component';
import { DashboardStateService } from '../../core/services/dashboard-state.service';

@Component({
  selector: 'app-trading',
  standalone: true,
  imports: [
    CommonModule,
    IonContent, IonSegment, IonSegmentButton, IonLabel, IonRefresher, IonRefresherContent,
    AppHeaderComponent, MarketStripComponent, ConnectionBannerComponent,
    RecommendComponent, LiveTradesComponent, PnlComponent,
  ],
  template: `
    <app-header title="Trading"></app-header>

    <ion-content class="ion-padding">
      <ion-refresher slot="fixed" (ionRefresh)="doRefresh($event)">
        <ion-refresher-content></ion-refresher-content>
      </ion-refresher>

      <app-connection-banner></app-connection-banner>
      <app-market-strip></app-market-strip>

      <ion-segment [value]="segment" (ionChange)="segment = $any($event.detail.value)" style="margin:6px 0 12px;">
        <ion-segment-button value="recommend"><ion-label>Recommend</ion-label></ion-segment-button>
        <ion-segment-button value="live"><ion-label>Live</ion-label></ion-segment-button>
        <ion-segment-button value="pnl"><ion-label>P&L</ion-label></ion-segment-button>
      </ion-segment>

      <!-- [hidden] keeps each segment mounted so an in-progress trade card survives a peek at Live -->
      <app-recommend [hidden]="segment !== 'recommend'" (viewLive)="segment = 'live'"></app-recommend>
      <app-live-trades [hidden]="segment !== 'live'"></app-live-trades>
      <app-pnl [hidden]="segment !== 'pnl'"></app-pnl>
    </ion-content>
  `,
})
export class TradingPage {
  private state = inject(DashboardStateService);
  segment: 'recommend' | 'live' | 'pnl' = 'recommend';

  doRefresh(event: CustomEvent): void {
    this.state.refreshSignal();
    this.state.refreshTrades();
    setTimeout(() => (event.target as HTMLIonRefresherElement).complete(), 1200);
  }
}
