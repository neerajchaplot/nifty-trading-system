export interface AuthCallbackTokens {
  accessToken: string;
  refreshToken: string | null;
}

/**
 * Parse the tokens delivered by the backend on the OAuth deep link:
 *   zupptrade://auth/callback#access_token=...&refresh_token=...&expires_in=...
 * Returns null for any URL that isn't our callback or is missing the access token.
 * Pure function — kept separate from AppComponent so it is unit-testable without Capacitor.
 */
export function parseAuthCallback(url: string): AuthCallbackTokens | null {
  if (!url.startsWith('zupptrade://auth/callback')) return null;
  const fragment = url.split('#')[1] ?? '';
  const params = new URLSearchParams(fragment);
  const accessToken = params.get('access_token');
  if (!accessToken) return null;
  return { accessToken, refreshToken: params.get('refresh_token') };
}
