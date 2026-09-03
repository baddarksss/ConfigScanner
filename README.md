# ConfigScanner

Android app that scans your proxy configs (VLESS/VMess/Trojan/SS/Hysteria2),
tests each one through a local SOCKS5 proxy and reports the exit country —
with a live animated water-circle progress.

- Bilingual (English / Persian)
- Dark & Light themes (iOS lock-screen glass look)
- Built-in Xray-core (for VLESS/VMess/Trojan/SS) + native Hysteria client (for Hysteria2, Salamander/Gecko)
- Parallel testing, adjustable timeout, channel-name tagging
- Copy / save results, save app log as .txt
- In-app updates for both the app (this repo's releases) and the Xray core (stable / beta)

## App identity (read before upgrading)

- **v1.0.14 and later** use the package `com.configscanner` and a new signing
  certificate. Versions **v1.0.13 and earlier** used `com.wpnfa.configscan`.
  → To move from ≤ v1.0.13, **uninstall the old app first**, then install v1.0.14+.
- Updates **within** v1.0.14+ install over each other (same package + key),
  including via the built-in in-app updater (GitHub releases).

## In-app updates

- **App update** (Settings → App update): checks the latest release of this
  repo, downloads the APK and installs it (the user grants “install unknown
  apps” once; if the FileProvider route fails it falls back to the public
  Downloads folder). Downloaded APKs are kept, so a failed install is retried
  without re-downloading.
- **Core update** (Settings → Xray core): checks Xray-core releases (stable,
  or the newest pre-release with the beta tick), downloads the zip into app
  storage (cached), extracts the `xray` binary and installs it — an in-app
  copy wins over the bundled one. It never downgrades the running core.
  Some devices block executing binaries from app storage; in that case the app
  suggests updating the app itself (new cores ship bundled in new app releases).

## Releases

APK builds are published as GitHub releases — see the [releases page](https://github.com/baddarksss/ConfigScanner/releases).

## Building

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34), Gradle 8.11.

```bash
cd proj
# 1) native binaries live in app/src/main/jniLibs/arm64-v8a (see below)
# 2) build (release-key signing is enabled when CFGSCAN_KEY_PASS is set)
CFGSCAN_KEY_PASS=... JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk \
  /path/to/gradle-8.11.1/bin/gradle assembleDebug
# APK: proj/app/build/outputs/apk/debug/app-debug.apk
```

### Native binaries (jniLibs/arm64-v8a)

| File | Source |
|------|--------|
| `libxray.so` | Xray-core release asset `Xray-android-arm64-v8a.zip` (the `xray` binary inside) — https://github.com/XTLS/Xray-core/releases |
| `libhysteria.so` | Hysteria release asset `hysteria-android-arm64` — https://github.com/HyNetworks/hysteria/releases |

### Signing

`build.gradle` reads the signing password from the `CFGSCAN_KEY_PASS`
environment variable and the keystore from the repo parent directory
(`cfgscan-v2.keystore`). When the variable is set, builds are signed with the
release key so updates install over previous v1.0.14+ versions; otherwise the
debug key is used.

---

## بیلد خودکار APK (GitHub Actions)

با هر پوش روی `main`، اپ به‌صورت خودکار بیلد می‌شود (خروجی در تب **Actions → Artifacts**).

**ریلیز:** کافیه یک تگ بزنی — اکشن APK را بیلد و در **Releases** می‌گذارد (همان جایی که آپدیت درون‌برنامه‌ای می‌خواند):

```bash
git tag v1.0.44 && git push origin v1.0.44
```

### امضای APK (برای آپدیت درجا — مهم)

اندروید اجازه نمی‌دهد APK با امضای متفاوت روی نسخه نصب‌شده آپدیت شود. برای اینکه APK ساخت CI با کلید خودت امضا شود، یک‌بار این دو Secret را در ریپو اضافه کن
(Settings → Secrets and variables → Actions):

| Secret | مقدار |
|---|---|
| `CFGSCAN_KEYSTORE_BASE64` | خروجی `base64 -w0 cfgscan-v2.keystore` |
| `CFGSCAN_KEY_PASS` | پسورد کی‌استور |

بدون این Secrets هم بیلد انجام می‌شود ولی APK با کلید debug امضا می‌شود (فقط نصب تازه، نه آپدیت). بیلد لوکال با کلید خودت مثل قبل کار می‌کند:

```bash
cd proj && CFGSCAN_KEY_PASS=... gradle assembleRelease
```
