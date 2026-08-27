import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Browser } from '@capacitor/browser';
import { Preferences } from '@capacitor/preferences';
import { environment } from '../../../environments/environment';

const ACCESS_KEY = 'zupp.access_token';
const REFRESH_KEY = 'zupp.refresh_token';

export type AuthProvider = 'google' | 'upstox';

/**
 * Mobile end-user session. Unlike the web app, login is a NATIVE flow: the system browser
 * handles the OAuth redirect and the backend hands tokens back via a `zupptrade://` deep link
 * (captured in AppComponent). Tokens live in device storage (Preferences) with an in-memory
 * copy so the auth interceptor can read the access token synchronously.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly base = environment.agentUserBaseUrl; // https://<host>/api/agent-user
  private access: string | null = null;
  private refreshTok: string | null = null;

  constructor(private http: HttpClient) {}

  /** Load any persisted tokens at app startup. Call before the first guarded navigation. */
  async init(): Promise<void> {
    this.access = (await Preferences.get({ key: ACCESS_KEY })).value;
    this.refreshTok = (await Preferences.get({ key: REFRESH_KEY })).value;
  }

  get accessToken(): string | null { return this.access; }
  isAuthenticated(): boolean { return !!this.access; }

  /** Open the SYSTEM browser (not the web view) to the provider login, tagged client=mobile. */
  async startLogin(provider: AuthProvider): Promise<void> {
    await Browser.open({ url: `${this.base}/auth/${provider}/login?client=mobile` });
  }

  /** Called by the deep-link handler with tokens parsed from zupptrade://auth/callback#... */
  async setTokens(access: string, refresh: string | null): Promise<void> {
    this.access = access;
    await Preferences.set({ key: ACCESS_KEY, value: access });
    if (refresh) {
      this.refreshTok = refresh;
      await Preferences.set({ key: REFRESH_KEY, value: refresh });
    }
  }

  async logout(): Promise<void> {
    this.access = null;
    this.refreshTok = null;
    await Preferences.remove({ key: ACCESS_KEY });
    await Preferences.remove({ key: REFRESH_KEY });
    // Best-effort server notification; stateless logout, ignore result.
    this.http.post(`${this.base}/auth/logout`, {}).subscribe({ error: () => {} });
  }
}
