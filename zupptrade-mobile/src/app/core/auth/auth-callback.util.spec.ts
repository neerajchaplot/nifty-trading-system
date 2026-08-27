import { parseAuthCallback } from './auth-callback.util';

describe('parseAuthCallback', () => {
  it('parses access + refresh tokens from a valid deep link', () => {
    const r = parseAuthCallback(
      'zupptrade://auth/callback#access_token=abc&refresh_token=def&expires_in=1800',
    );
    expect(r).toEqual({ accessToken: 'abc', refreshToken: 'def' });
  });

  it('returns refreshToken null when absent', () => {
    const r = parseAuthCallback('zupptrade://auth/callback#access_token=abc');
    expect(r).toEqual({ accessToken: 'abc', refreshToken: null });
  });

  it('rejects a URL with a different scheme/host', () => {
    expect(
      parseAuthCallback('https://evil.com/auth/callback#access_token=abc'),
    ).toBeNull();
  });

  it('returns null when access_token is missing', () => {
    expect(parseAuthCallback('zupptrade://auth/callback#state=x')).toBeNull();
  });
});
