import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IonIcon } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { cloudOfflineOutline } from 'ionicons/icons';
import { DashboardStateService } from '../../../core/services/dashboard-state.service';

/** Thin banner shown when live polling can't reach the server — warns the data on screen is stale. */
@Component({
  selector: 'app-connection-banner',
  standalone: true,
  imports: [CommonModule, IonIcon],
  template: `
    <div class="conn" *ngIf="offline$ | async">
      <ion-icon name="cloud-offline-outline"></ion-icon>
      <span>Can't reach the server — showing last known data.</span>
    </div>
  `,
  styles: [`
    .conn {
      display:flex; align-items:center; gap:8px;
      background: rgba(217,119,6,0.12); border: 1px solid rgba(217,119,6,0.35);
      color: var(--zt-amber); font-size: 12px; font-weight: 600;
      border-radius: 8px; padding: 8px 12px; margin-bottom: 10px;
    }
    .conn ion-icon { font-size: 16px; }
  `],
})
export class ConnectionBannerComponent {
  private state = inject(DashboardStateService);
  offline$ = this.state.connectionError;
  constructor() { addIcons({ cloudOfflineOutline }); }
}
