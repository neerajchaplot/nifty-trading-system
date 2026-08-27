import { Component, OnInit, NgZone, inject } from '@angular/core';
import { Router } from '@angular/router';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { App as CapacitorApp } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { AuthService } from './core/services/auth.service';
import { AgentUserService } from './core/services/agent-user.service';
import { UserStateService } from './core/services/user-state.service';
import { parseAuthCallback } from './core/auth/auth-callback.util';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [IonApp, IonRouterOutlet],
  template: `<ion-app><ion-router-outlet /></ion-app>`,
})
export class AppComponent implements OnInit {
  private auth = inject(AuthService);
  private agentUser = inject(AgentUserService);
  private userState = inject(UserStateService);
  private router = inject(Router);
  private zone = inject(NgZone);

  async ngOnInit(): Promise<void> {
    // 1) Restore any persisted session before the first guarded navigation.
    await this.auth.init();

    // 2) Capture the OAuth deep link: zupptrade://auth/callback#access_token=...&refresh_token=...
    CapacitorApp.addListener('appUrlOpen', ({ url }) => {
      // Runs outside Angular's zone (native event) — re-enter so routing/UI update.
      this.zone.run(() => this.handleAuthCallback(url));
    });

    // 3) If already signed in from a previous run, load the profile.
    if (this.auth.isAuthenticated()) {
      this.loadProfile();
    }
  }

  private async handleAuthCallback(url: string): Promise<void> {
    const tokens = parseAuthCallback(url);
    if (!tokens) return;

    await this.auth.setTokens(tokens.accessToken, tokens.refreshToken);
    await Browser.close().catch(() => {});
    this.loadProfile();
    this.router.navigate(['/'], { replaceUrl: true });
  }

  private loadProfile(): void {
    this.agentUser.me().subscribe({
      next: profile => this.userState.setProfile(profile),
      error: err => console.error('Failed to load user profile', err),
    });
  }
}
