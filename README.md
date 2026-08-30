# Config Scanner 🌍

اپ اندروید برای تست کانفیگ‌های پروکسی — هر لینکی رو بچسبون، کشور خروجی رو بدون flag نشون می‌ده و به اسم سرور اضافه می‌کنه.

**Android app for batch-testing proxy configs** — paste links, and it reports each server's exit country (with flag) and renames it.

## Protocols / پروتکل‌ها

| Protocol | Transport | Notes |
|---|---|---|
| VLESS | Reality / TLS / WS / gRPC | incl. `insecure=1` (cert pinning) |
| VMess | WS / gRPC / TCP | |
| Trojan | TLS / WS / gRPC | |
| Shadowsocks | TCP / UDP | |
| Hysteria2 | QUIC | **native Hysteria client** — Salamander & Gecko obfs supported |

## Features / امکانات

-  Parallel testing (1–10, adjustable) with progress percentage
- ⏱ Per-server timeout (5–60s) — a dead server never blocks the run
- 📤 Results: one line per server, country flag + name + optional channel suffix — copy or save to file
-  Xray core (26.x) bundled in-app, with **in-app updater** (stable / pre-release / from file)
- 🧩 Hysteria native client (2.12.x) bundled for hy2 links
- 🌐 Bilingual UI: English / Persian (System / فارسی / English)
- 📄 In-app log for debugging
- 🌍 Country detection via online IP services (majority vote of 3)

## Install / نصب

APK files are in the [Releases](../../releases) section.

> Note: from v1.0.5 the app is signed with a stable key — updates install **over** the previous version. (v1.0.0–v1.0.4 were signed with different keys and must be uninstalled first.)

## Build / بیلد

- JDK 21, Gradle 8.11.1, AGP 8.5.2
- `minSdk 26` (Android 8.0+), `targetSdk 34`
- Package: `com.wpnfa.configscan`

```
./gradlew assembleDebug
```

Both proxy cores are shipped via `jniLibs` (`libxray.so`, `libhysteria.so`) so the package installer places them where Android allows exec (see [issue 128554619](https://issuetracker.google.com/issues/128554619)).

## Changelog / چرایژن‌لگ

See the About dialog in-app or each release's notes.

- **v1.0.6** — native Hysteria client for hy2 (Salamander/Gecko obfs now work); obfs-password parse fix; Xray 26.x log field fix
- **v1.0.5** — bilingual FA/EN + language switch; generic channel hint; stable signing key (updates install over)
- **v1.0.4** — Hysteria2 x25519 + serverName fixes; insecure=1 cert pinning; UI reset after run
- **v1.0.3** — allowInsecure fix for Xray 26.x; xray error tail in log
- **v1.0.2** — core moved to jniLibs (exec error=13 fixed)
- **v1.0.1** — debug log button
- **v1.0.0** — initial release
