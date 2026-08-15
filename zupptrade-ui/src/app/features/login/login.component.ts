import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthProvider, AuthService } from '../../core/services/auth.service';

/**
 * Login screen. Two providers: Upstox = live trading account, Google = simulation account
 * (live data, paper fills). Clicking a button full-page navigates to the provider via agent-user.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="login-page">
      <div class="login-card">
        <img src="assets/zupp-logo.jpg" alt="ZuppTrade" class="logo" />
        <h1>Welcome to ZuppTrade</h1>
        <p class="sub">Nifty&nbsp;50 options — sign in to continue</p>

        <button class="provider provider-upstox" (click)="login('upstox')">
          <span>Sign in with Upstox</span>
          <span class="pill pill-live">Live</span>
        </button>

        <button class="provider provider-google" (click)="login('google')">
          <span>Sign in with Google</span>
          <span class="pill pill-sim">Simulation</span>
        </button>

        <p class="hint">
          <strong>Upstox</strong> connects your live trading account.
          <strong>Google</strong> gives a simulation account — real market data, paper trades.
        </p>
      </div>
      <p class="foot">Automated Nifty&nbsp;50 options — consistency over return maximisation.</p>
    </div>
  `,
  styles: [`
    .login-page {
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 18px;
      background: radial-gradient(1200px 600px at 50% -10%, #EAF0FB 0%, #F6F8FC 55%, #EEF2F8 100%);
      padding: 24px;
    }
    .login-card {
      width: 100%;
      max-width: 380px;
      background: #fff;
      border: 1px solid #E2E8F0;
      border-radius: 16px;
      box-shadow: 0 10px 40px rgba(15,23,42,.10);
      padding: 32px 28px 26px;
      text-align: center;
      display: flex;
      flex-direction: column;
      align-items: center;
    }
    .logo { height: 54px; width: auto; object-fit: contain; margin-bottom: 18px; }
    h1 { font-size: 20px; font-weight: 800; color: #0F172A; margin: 0 0 4px; letter-spacing: -.01em; }
    .sub { font-size: 13px; color: #64748B; margin: 0 0 22px; }

    .provider {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      padding: 12px 16px;
      border-radius: 10px;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
      border: 1.5px solid transparent;
      transition: transform .06s ease, box-shadow .15s ease, background .15s ease;
      margin-top: 12px;
    }
    .provider:active { transform: translateY(1px); }
    .provider-upstox {
      background: #2563EB; color: #fff;
      box-shadow: 0 4px 14px rgba(37,99,235,.28);
    }
    .provider-upstox:hover { background: #1E40AF; }
    .provider-google {
      background: #fff; color: #334155; border-color: #D9E0EA;
    }
    .provider-google:hover { background: #F8FAFC; border-color: #C6D1E1; }

    .pill {
      font-size: 10px; font-weight: 800; letter-spacing: .04em;
      padding: 2px 7px; border-radius: 999px; text-transform: uppercase;
    }
    .pill-live { background: rgba(255,255,255,.22); color: #fff; }
    .pill-sim  { background: #EFF6FF; color: #2563EB; }

    .hint { font-size: 11.5px; color: #94A3B8; line-height: 1.5; margin: 20px 0 0; }
    .hint strong { color: #64748B; }
    .foot { font-size: 11px; color: #94A3B8; margin: 0; }
  `],
})
export class LoginComponent {
  constructor(private auth: AuthService) {}

  login(provider: AuthProvider): void {
    this.auth.startLogin(provider);
  }
}
