import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IonContent } from '@ionic/angular/standalone';
import { AuthService, AuthProvider } from '../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, IonContent],
  template: `
    <ion-content class="ion-padding">
      <div class="login-wrap">
        <img src="assets/wordmark-transparent.png" alt="ZuppTrade" class="login-logo">
        <div class="login-tag">Nifty 50 Options — sign in to continue</div>

        <button class="login-btn login-upstox" (click)="login('upstox')">
          <span class="btn-title">Continue with Upstox</span>
          <span class="btn-sub">Live trading account</span>
        </button>

        <button class="login-btn login-google" (click)="login('google')">
          <span class="btn-title">Continue with Google</span>
          <span class="btn-sub">Simulation account</span>
        </button>

        <div class="login-note">
          You'll be taken to a secure browser to sign in, then returned to the app.
        </div>
      </div>
    </ion-content>
  `,
  styles: [`
    .login-wrap {
      min-height: 100%;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 14px;
      padding: 24px 8px;
    }
    .login-logo { height: 44px; width: auto; object-fit: contain; margin-bottom: 4px; }
    .login-tag { font-size: 13px; color: var(--zt-sub); margin-bottom: 12px; text-align: center; }
    .login-btn {
      width: 100%;
      max-width: 340px;
      border: 1px solid var(--zt-border);
      border-radius: 12px;
      padding: 14px 16px;
      background: #fff;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
      cursor: pointer;
      font-family: inherit;
    }
    .login-btn:active { background: #F1F5F9; }
    .login-upstox { border-color: #BBF7D0; }
    .login-google { border-color: #BFDBFE; }
    .btn-title { font-size: 15px; font-weight: 700; color: var(--zt-text); }
    .btn-sub { font-size: 11px; color: var(--zt-muted); }
    .login-note { font-size: 11px; color: var(--zt-muted); text-align: center; max-width: 300px; margin-top: 8px; }
  `],
})
export class LoginPage {
  constructor(private auth: AuthService) {}

  login(provider: AuthProvider): void {
    this.auth.startLogin(provider);
  }
}
