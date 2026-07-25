import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.zupptrade.mobile',
  appName: 'ZuppTrade',
  webDir: 'www',
  server: {
    androidScheme: 'https',
    // --- DEV ONLY: live-reload against the LAN dev server ---
    // Loads the app from `npm run start:lan` on the PC so the ng-serve proxy
    // still forwards /api/* to the backend agents. cleartext allows plain http.
    // REMOVE `url` + `cleartext` before a real/bundled release build.
    url: 'http://192.168.1.5:4201',
    cleartext: true,
  },
};

export default config;
