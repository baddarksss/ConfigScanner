# ConfigScanner

Android app that scans your proxy configs (VLESS/VMess/Trojan/SS/Hysteria2),
tests each one through a local SOCKS5 proxy and reports the exit country —
with a live animated water-circle progress.

- Bilingual (English / Persian)
- Dark & Light themes (iOS lock-screen glass look)
- Built-in Xray-core (for VLESS/VMess/Trojan/SS) + native Hysteria client (for Hysteria2, Salamander/Gecko)
- Parallel testing, adjustable timeout, channel-name tagging
- Copy / save results, save app log as .txt
- Self-update for the Xray core (stable / pre-release / local file)

## Releases

APK builds are published as GitHub releases — see the [releases page](https://github.com/baddarksss/ConfigScanner/releases).
Install over previous versions (same signing key).

## Building

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34), Gradle 8.11.

```bash
cd proj
# 1) place the two native binaries (see below)
mkdir -p app/src/main/jniLibs/arm64-v8a
# 2) build
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk \
  /path/to/gradle-8.11.1/bin/gradle assembleDebug
# APK: proj/app/build/outputs/apk/debug/app-debug.apk
```

### Native binaries (jniLibs/arm64-v8a)

| File | Source |
|------|--------|
| `libxray.so` | Xray-core release asset `Xray-android-arm64-v8a.zip` (the `xray` binary inside) — https://github.com/XTLS/Xray-core/releases |
| `libhysteria.so` | Hysteria release asset `hysteria-android-arm64` — https://github.com/HyNetworks/hysteria/releases |

### Signing

Debug builds are signed with the local debug keystore. The released APKs are
signed with a dedicated keystore so updates install over previous versions.
