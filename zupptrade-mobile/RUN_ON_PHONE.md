# Run ZuppTrade Mobile on a Physical Android Phone

Live-reload setup: the phone runs a native APK whose web view loads from the
PC's dev server over Wi-Fi. Code changes hot-reload automatically; you only
rebuild the APK when the PC's IP changes or native config changes.

Key facts for this machine:
- Project: `C:\3CGrp\nifty-trading-system\zupptrade-mobile`
- App id: `com.zupptrade.mobile`
- adb: `C:\Users\nchap\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Dev server port: `4201`
- The app's target IP is baked into `capacitor.config.ts` → `server.url`
  (currently `http://192.168.1.5:4201`).

---

## A. Daily run — app ALREADY installed, IP unchanged  (the common case)

1. **Start the dev server** (leave this window OPEN the whole session):
   ```powershell
   cd "C:\3CGrp\nifty-trading-system\zupptrade-mobile"
   npm run start:lan
   ```
2. **Wait** for this line and note the IP:
   ```
   ➜  Network:  http://192.168.1.X:4201/
   ```
3. **Check the IP matches** the one in `capacitor.config.ts` (`server.url`).
   - **Same IP?** Just open the **ZuppTrade** app on the phone. Done. ✅
     (If it shows a blank/error page, force-stop the app and reopen — the web
     view does not auto-retry a failed load.)
   - **Different IP?** → do **Section C** (rebuild with the new IP), once.

> Phone and PC must be on the **same Wi-Fi**. No USB / adb needed just to *run*
> the already-installed app — only the server needs to be reachable.

---

## B. Connect the phone over Wi-Fi (only needed to INSTALL / rebuild)

Needed for Section C and D, not for Section A.

1. Phone: **Settings → Developer options → Wireless debugging → ON**.
   (If greyed out: **Settings → Security and privacy → Auto Blocker → OFF**.)
2. Check if already connected:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
   ```
   - Shows a `...:PORT   device` line → you're connected, skip to C/D.
3. If NOT listed, connect using the **IP address & Port** shown on the main
   Wireless debugging screen:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.4:PORT
   ```
4. If connect fails, re-pair (tap **"Pair device with pairing code"** for a
   fresh port + 6-digit code):
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pair 192.168.1.4:PAIRPORT CODE
   ```
   then repeat step 3. (Pair port ≠ connect port; both change each toggle.)

---

## C. Rebuild + reinstall (PC IP changed, or you changed native/Capacitor config)

Do **Section B** first so the phone shows in `adb devices`. Then:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "192.168.1.4:PORT"   # <-- your device id from `adb devices`

# 1. Point the app at the current PC IP (edit this file, save):
#    capacitor.config.ts  ->  server.url: 'http://<NEW-PC-IP>:4201'

# 2. Copy config into the android project
cd "C:\3CGrp\nifty-trading-system\zupptrade-mobile"
npx cap copy android

# 3. Rebuild the APK
cd android
.\gradlew.bat assembleDebug --no-daemon
cd ..

# 4. Reinstall + relaunch
& $adb -s $dev install -r "android\app\build\outputs\apk\debug\app-debug.apk"
& $adb -s $dev shell am force-stop com.zupptrade.mobile
& $adb -s $dev shell monkey -p com.zupptrade.mobile -c android.intent.category.LAUNCHER 1
```

> **Kill this recurring pain:** give the PC a fixed IP (router DHCP reservation
> or a static IP). Then the IP never changes and Section C is never needed —
> daily use becomes just Section A.

---

## D. One-time setup (already done on this machine — only for a fresh PC)

1. Install **Android Studio** + SDK (Standard wizard) and **JDK 21**.
2. Set `ANDROID_HOME` = `C:\Users\<you>\AppData\Local\Android\Sdk` (User env).
3. Ensure `android/local.properties` contains:
   `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`
4. Add the platform once: `npx cap add android`.
5. Pair the phone once (Section B).

---

## Troubleshooting

| Symptom | Cause / Fix |
|---|---|
| App blank or shows an error page | Dev server down, or PC IP changed. Check `Network:` line; if IP differs → Section C. Force-stop + reopen app. |
| `port 4201 already in use` | A stale server is squatting. Kill it: `Get-NetTCPConnection -LocalPort 4201 \| ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }` then restart. |
| Browser can't reach `http://<PC-IP>:4201` but port is "in use" | Old server bound to `localhost` only. Kill it (above), rerun `npm run start:lan` (binds `0.0.0.0`). |
| `adb devices` empty | Re-toggle Wireless debugging, then Section B step 3/4. |
| `'gradlew' is not recognized` | Don't use `npx cap run android`; build with `.\gradlew.bat assembleDebug` (Section C). |
| Gradle "SDK location not found" | Add/fix `android/local.properties` `sdk.dir` line. |
| `Failed to load user profile` in logs | Known: `agent-user/me` 404; app falls back to a default profile. Harmless. |
