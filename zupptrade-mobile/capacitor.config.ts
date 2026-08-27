import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.zupptrade.mobile',
  appName: 'ZuppTrade',
  webDir: 'www',
  server: {
    androidScheme: 'https',
  },
  plugins: {
    // Route Angular HttpClient (XHR/fetch) through native HTTP: handles TLS natively and
    // bypasses browser CORS, so the app can call the cross-origin Azure HTTPS API directly.
    CapacitorHttp: {
      enabled: true,
    },
  },
};

export default config;
