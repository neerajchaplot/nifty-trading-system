import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import {
  IonMenu, IonHeader, IonToolbar, IonTitle, IonContent, IonList, IonItem,
  IonIcon, IonLabel, IonMenuToggle, IonRouterOutlet, IonBadge, IonFooter, IonAlert,
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { trendingUpOutline, barChartOutline, documentTextOutline, personOutline, logOutOutline } from 'ionicons/icons';
import { AuthService } from '../core/services/auth.service';
import { UserStateService } from '../core/services/user-state.service';
import { initials } from '../shared/util/profile.util';

/** App shell: the left navigation drawer (Trading/Futures/Audit/Profile + identity/logout) and the routed content outlet. */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    CommonModule, RouterLink, RouterLinkActive,
    IonMenu, IonHeader, IonToolbar, IonTitle, IonContent, IonList, IonItem,
    IonIcon, IonLabel, IonMenuToggle, IonRouterOutlet, IonBadge, IonFooter, IonAlert,
  ],
  template: `
    <ion-menu contentId="main-content" type="overlay">
      <ion-header>
        <ion-toolbar style="--background:#ffffff;">
          <ion-title style="color:#1B4FA8; font-weight:800;">ZuppTrade</ion-title>
        </ion-toolbar>
      </ion-header>

      <ion-content>
        <ion-list lines="none">
          <ion-menu-toggle [autoHide]="true">
            <ion-item routerLink="/trading" routerLinkActive="nav-active" detail="false">
              <ion-icon name="trending-up-outline" slot="start"></ion-icon><ion-label>Trading</ion-label>
            </ion-item>
            <ion-item routerLink="/futures" routerLinkActive="nav-active" detail="false">
              <ion-icon name="bar-chart-outline" slot="start"></ion-icon><ion-label>Futures</ion-label>
            </ion-item>
            <ion-item routerLink="/audit" routerLinkActive="nav-active" detail="false">
              <ion-icon name="document-text-outline" slot="start"></ion-icon><ion-label>Audit</ion-label>
            </ion-item>
            <ion-item routerLink="/profile" routerLinkActive="nav-active" detail="false">
              <ion-icon name="person-outline" slot="start"></ion-icon><ion-label>Profile</ion-label>
            </ion-item>
          </ion-menu-toggle>
        </ion-list>
      </ion-content>

      <ion-footer>
        <div style="padding:12px 16px; border-top:1px solid var(--zt-border);">
          <div style="display:flex; align-items:center; gap:10px;">
            <div class="menu-avatar">{{ avatar }}</div>
            <div style="min-width:0; flex:1;">
              <div style="font-size:13px; font-weight:700;">{{ name }}</div>
              <div style="font-size:11px; color:var(--zt-sub); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">{{ email }}</div>
            </div>
            <ion-badge [color]="isSim ? 'warning' : 'success'">{{ modeLabel }}</ion-badge>
          </div>
          <ion-menu-toggle [autoHide]="true">
            <button class="logout-btn" (click)="logoutOpen = true">
              <ion-icon name="log-out-outline"></ion-icon> Log out
            </button>
          </ion-menu-toggle>
        </div>
      </ion-footer>
    </ion-menu>

    <ion-router-outlet id="main-content"></ion-router-outlet>

    <ion-alert
      [isOpen]="logoutOpen"
      header="Log out?"
      message="You'll need to sign in again."
      [buttons]="logoutButtons"
      (didDismiss)="logoutOpen = false"></ion-alert>
  `,
  styles: [`
    .nav-active { --background: var(--zt-surface); --color: var(--zt-blue); font-weight: 700; }
    .menu-avatar { width:34px; height:34px; border-radius:50%; background:#DBEAFE; color:#1B4FA8; font-size:13px; font-weight:700; display:flex; align-items:center; justify-content:center; }
    .logout-btn { display:flex; align-items:center; gap:8px; width:100%; margin-top:12px; background:none; border:none; color:var(--zt-red); font-size:13px; font-weight:600; font-family:inherit; padding:6px 0; cursor:pointer; }
  `],
})
export class ShellComponent {
  private auth = inject(AuthService);
  private userState = inject(UserStateService);
  private router = inject(Router);

  constructor() {
    addIcons({ trendingUpOutline, barChartOutline, documentTextOutline, personOutline, logOutOutline });
  }

  get name(): string {
    const p = this.userState.profile;
    return p?.displayName || p?.email || p?.userId || 'Account';
  }
  get email(): string { return this.userState.profile?.email ?? ''; }
  get avatar(): string { const p = this.userState.profile; return initials(p?.displayName, p?.email); }
  get isSim(): boolean { return this.userState.profile?.accountMode === 'SIMULATION'; }
  get modeLabel(): string { return this.isSim ? 'SIM' : 'LIVE'; }

  logoutOpen = false;
  readonly logoutButtons = [
    { text: 'Cancel', role: 'cancel' },
    { text: 'Log out', role: 'confirm', handler: () => this.doLogout() },
  ];
  doLogout(): void { this.auth.logout(); this.router.navigate(['/login']); }
}
