import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

const ACCESS_KEY = 'zupp.access_token';
const REFRESH_KEY = 'zupp.refresh_token';

export type AuthProvider = 'google' | 'upstox';

/**
 * Holds the end-user session (JWT) and drives login/logout. Tokens are stored in localStorage;
 * the auth interceptor attaches the access token to every API call.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.agentUserBaseUrl; // /api/agent-user

  get accessToken(): string | null { return localStorage.getItem(ACCESS_KEY); }
  get refreshToken(): string | null { return localStorage.getItem(REFRESH_KEY); }

  isAuthenticated(): boolean { return !!this.accessToken; }

  setTokens(access: string, refresh: string | null): void {
    localStorage.setItem(ACCESS_KEY, access);
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh);
  }

  clear(): void {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  }

  /** Full-page navigate to the provider login (server 302s to Google/Upstox). */
  startLogin(provider: AuthProvider): void {
    window.location.href = `${this.base}/auth/${provider}/login`;
  }

  logout(): void {
    this.clear();
    // Best-effort server notification; ignore result (stateless logout).
    this.http.post(`${this.base}/auth/logout`, {}).subscribe({ error: () => {} });
  }

  refresh(): Observable<{ accessToken: string; refreshToken: string; accessExpiresInSeconds: number }> {
    return this.http.post<{ accessToken: string; refreshToken: string; accessExpiresInSeconds: number }>(
      `${this.base}/auth/refresh`, { refreshToken: this.refreshToken });
  }
}
