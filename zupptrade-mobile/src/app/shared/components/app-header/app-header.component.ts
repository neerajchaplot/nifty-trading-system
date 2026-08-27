import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import {
  IonHeader, IonToolbar, IonTitle, IonButtons, IonPopover, IonBadge,
} from '@ionic/angular/standalone';
import { AuthService } from '../../../core/services/auth.service';
import { UserStateService } from '../../../core/services/user-state.service';
import { initials } from '../../util/profile.util';

/**
 * Shared page header used by every tab: logo + title + an avatar that opens an account
 * menu (identity, Profile, Logout). Replaces the per-page inline toolbars.
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, IonHeader, IonToolbar, IonTitle, IonButtons, IonPopover, IonBadge],
  template: `
    <ion-header>
      <ion-toolbar style="--background:#ffffff; --border-color:#E2E8F0;">
        <ion-buttons slot="start">
          <img src="assets/zupp-logo.jpg" alt="ZuppTrade" style="height:32px;width:auto;margin-left:8px;object-fit:contain;">
        </ion-buttons>
        <ion-title style="color:#1B4FA8;">{{ title }}</ion-title>
        <ion-buttons slot="end">
          <button id="app-account-trigger" class="avatar-btn" aria-label="Account menu">{{ avatar }}</button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-popover trigger="app-account-trigger" [dismissOnSelect]="true" side="bottom" alignment="end">
      <ng-template>
        <div class="menu">
          <div class="menu-id">
            <div class="menu-avatar">{{ avatar }}</div>
            <div style="min-width:0;">
              <div class="menu-name">{{ name }}</div>
              <div class="menu-email" *ngIf="email">{{ email }}</div>
            </div>
            <ion-badge [color]="isSim ? 'warning' : 'success'" style="margin-left:auto;">{{ modeLabel }}</ion-badge>
          </div>
          <button class="menu-item" (click)="goProfile()">Profile and settings</button>
          <button class="menu-item menu-danger" (click)="logout()">Log out</button>
        </div>
      </ng-template>
    </ion-popover>
  `,
  styles: [`
    .avatar-btn {
      width:30px; height:30px; border-radius:50%;
      background:#DBEAFE; color:#1B4FA8;
      font-size:12px; font-weight:700; border:none;
      margin-right:8px; cursor:pointer; font-family:inherit;
    }
    .menu { min-width:230px; padding:6px; }
    .menu-id { display:flex; align-items:center; gap:10px; padding:10px; border-bottom:1px solid var(--zt-border); }
    .menu-avatar { width:36px; height:36px; border-radius:50%; background:#DBEAFE; color:#1B4FA8; font-size:13px; font-weight:700; display:flex; align-items:center; justify-content:center; }
    .menu-name { font-size:13px; font-weight:700; color:var(--zt-text); }
    .menu-email { font-size:11px; color:var(--zt-sub); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:150px; }
    .menu-item { display:block; width:100%; text-align:left; background:none; border:none; padding:11px 12px; font-size:13px; color:var(--zt-text); font-family:inherit; cursor:pointer; }
    .menu-item:active { background:var(--zt-surface); }
    .menu-danger { color:var(--zt-red); }
  `],
})
export class AppHeaderComponent {
  @Input() title = '';

  private auth = inject(AuthService);
  private userState = inject(UserStateService);
  private router = inject(Router);

  get name(): string {
    const p = this.userState.profile;
    return p?.displayName || p?.email || p?.userId || 'Account';
  }
  get email(): string { return this.userState.profile?.email ?? ''; }
  get avatar(): string {
    const p = this.userState.profile;
    return initials(p?.displayName, p?.email);
  }
  get isSim(): boolean { return this.userState.profile?.accountMode === 'SIMULATION'; }
  get modeLabel(): string { return this.isSim ? 'SIM' : 'LIVE'; }

  goProfile(): void { this.router.navigate(['/profile']); }
  logout(): void { this.auth.logout(); this.router.navigate(['/login']); }
}
