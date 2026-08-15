import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

/**
 * Lands here after a provider login. The tokens arrive in the URL fragment
 * (#access_token=…&refresh_token=…); we store them and route to the app. Navigating to '/' drops
 * the fragment so the tokens don't linger in the address bar.
 */
@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="cb">
      <div class="spinner" aria-hidden="true"></div>
      <p>{{ message }}</p>
    </div>
  `,
  styles: [`
    .cb {
      min-height: 100vh;
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px;
      background: #F6F8FC; color: #475569; font-size: 14px;
      font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    }
    .spinner {
      width: 26px; height: 26px; border-radius: 50%;
      border: 3px solid #DCE3EE; border-top-color: #2563EB;
      animation: spin .7s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) { .spinner { animation: none; } }
  `],
})
export class AuthCallbackComponent implements OnInit {
  message = 'Signing you in…';

  constructor(private auth: AuthService, private router: Router) {}

  ngOnInit(): void {
    const hash = window.location.hash.startsWith('#') ? window.location.hash.substring(1) : '';
    const params = new URLSearchParams(hash);
    const access = params.get('access_token');
    const refresh = params.get('refresh_token');

    if (access) {
      this.auth.setTokens(access, refresh);
      this.router.navigateByUrl('/');
    } else {
      this.message = 'Sign-in failed — returning to login…';
      this.router.navigate(['/login']);
    }
  }
}
