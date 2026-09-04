# ConfigScanner

**[فارسی 🇮🇷](README.fa.md) | English 🇬🇧**

![Release](https://img.shields.io/github/v/release/baddarksss/ConfigScanner?label=version&color=2E6BFF)
![CI](https://img.shields.io/github/actions/workflow/status/baddarksss/ConfigScanner/android.yml?branch=main&label=build&color=35D8C4)
![Platform](https://img.shields.io/badge/platform-Android%208%2B%20(arm64)-1E3A75)

Android app that scans your proxy configs (VLESS / VMess / Trojan / Shadowsocks /
Hysteria2 and more), tests each one through a local SOCKS5 tunnel and reports
the exit country — with a live animated water-circle progress and clean,
ready-to-post output.

## ✨ Features

**Scan engine**
- Protocols: **VLESS** (Reality / TLS / WS / gRPC / xHTTP + PQC encryption),
  **VMess**, **Trojan**, **Shadowsocks**, **Hysteria2** (native client with
  Salamander / Gecko obfs), plus parsing-only reporting for SSR, TUIC,
  ShadowTLS, AnyTLS, SNIc
- Bundled **Xray-core** (updatable in-app) + native **Hysteria** client
- TCP fragment (`fm=`), ECH, xhttp padding — panel extras are carried over
- Parallel testing (1–10 workers), per-server timeout, atomic port allocation
- Self-signed servers: the leaf certificate is pinned automatically

**Results & output**
- Working links renamed to `🇩🇪 Country | your-channel` (clean, no counters)
- Exit country voted by **6 geo services** in parallel — no single-service flukes
- Copy all links / working links only / save as `.txt`

**Caption builder** (Caption tab)
- Caption template with `{{FLAGS}}` placeholder
- Country emoji codes (premium emoji ids) per country, with search
- **Message for users** — a separate text block with its own copy button
- Backup/restore country codes as a `XX=code` **file** — the same file works
  in the [companion Telegram bot](https://github.com/baddarksss/ConfigScannerBot)

**Look & feel**
- 🎨 **Material You** — follows the wallpaper color palette on Android 12+
  (toggle in Settings), themed app icon on Android 13+
- Dark & Light themes, English + Persian UI, per-output country name language

## 📥 Download & Install

1. Grab the latest APK from the
   [**Releases**](https://github.com/baddarksss/ConfigScanner/releases) page
   (or use the in-app updater).
2. Requires **Android 8.0+ (arm64)**.
3. First install: allow "install unknown apps" for your browser/file manager
   when asked.

> Moving from very old versions (`com.wpnfa.configscan`, ≤ v1.0.13)?
> Uninstall the old app first — the package name changed to `com.configscanner`.
> Within v1.0.14+ everything updates in place, including through the built-in updater.

## 🚀 Usage

**Test tab** — paste configs (or 📄 pick a `.txt`), hit **🚀 Start test**,
watch the water circle fill. Results appear line by line: ✅ working (renamed
with the country), ⚠️ connected-but-unknown-country, ❌ unreachable, and skips
for protocols the core can't run.

**Settings tab** — channel name + switch (adds `| your-channel` to output
names), parallel workers, timeout, system color palette (Material You),
theme, app language, Xray-core update/test, app update, log & about.

**Caption tab** — edit the caption template, set country emoji codes, preview
the flags of the last run, and keep a **Message for users** block that you copy
on its own. Backup or restore all codes as one file, and copy them to the
clipboard in the exact format the Telegram bot understands.

## 🔄 In-app updates

- **App update** (Settings → App update): checks this repo's latest release,
  downloads the APK and hands it to the installer. Failed installs are retried
  from the cached APK without re-downloading.
- **Xray core update** (Settings → Xray core): pulls the newest stable (or
  pre-release with the beta tick) Xray-core, verifies and installs it into app
  storage — never downgrades. On devices that block executing updated binaries,
  the app suggests installing a newer APK (new releases bundle a fresh core).

## 🤖 Companion Telegram bot

[**ConfigScannerBot**](https://github.com/baddarksss/ConfigScannerBot) runs the
same scan engine on a server: send it configs, get the working links, flags,
caption and users-message as Telegram messages — and sync country emoji codes
both ways with this app using the same `XX=code` backup file.

## 🛠 Building from source

Requirements: **JDK 17+**, **Android SDK** (platform 34, build-tools 34),
**Gradle 8.7+**.

```bash
cd proj
gradle assembleDebug    # APK: app/build/outputs/apk/debug/
gradle assembleRelease  # APK: app/build/outputs/apk/release/
```

### Native binaries (`app/src/main/jniLibs/arm64-v8a`)

| File | Source |
|------|--------|
| `libxray.so` | Xray-core release asset `Xray-android-arm64-v8a.zip` (the `xray` binary inside) — https://github.com/XTLS/Xray-core/releases |
| `libhysteria.so` | Hysteria release asset `hysteria-android-arm64` — https://github.com/HyNetworks/hysteria/releases |

## ⚙️ CI

Every push to `main` is built automatically by
[GitHub Actions](.github/workflows/android.yml) — the APK lands in
**Actions → Artifacts**. Pushing a version tag (`git tag v1.0.45 &&
git push origin v1.0.45`) publishes the APK to
[**Releases**](https://github.com/baddarksss/ConfigScanner/releases), which is
exactly where the in-app updater looks.

## ❓ FAQ

- **The app kills the scan in the background** — disable battery optimization
  for ConfigScanner; a progress notification keeps the run alive.
- **A server is marked ❌ but works in my client** — the test opens a full
  tunnel and queries 6 geo services; some servers only allow browser-like
  traffic. Raise the timeout and try again.
- **"Core update blocked"** — your device refuses to execute updated binaries
  from app storage (SELinux). Install the newest app release instead; it ships
  with an updated core.
- **Where do results go?** — they stay in the output box until you clear them;
  use Copy / Save to export.
