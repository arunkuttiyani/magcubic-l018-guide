# Magcubic L018 guide

Debloat, a clean Apple-TV-style launcher, a whole-device ad/tracker/malware DNS firewall, and
boot/app-speed fixes for the **Magcubic L018** projector (Allwinner H726, Android 14) — rooted
with [Magisk](https://github.com/topjohnwu/Magisk). Built and tested on a real unit.

**Read [MANUAL.md](MANUAL.md)** for the full step-by-step guide and the reasoning behind each
step, or just run:

```bash
./setup.sh
```

from a computer with `adb` connected to your (already rooted) box to do all of it in one go.

- [`launcher/`](launcher) — Lite Home, a plain-Java (no Gradle) Android TV launcher
- [`ad-blocker/`](ad-blocker) — Simple Ad Blocker, a local-VPN DNS filter (~670k domains:
  ads, trackers, phishing, malware)
- [`scripts/`](scripts) — Magisk boot scripts (telemetry/debug-console blocking, optional
  weekly app recompile)

Not affiliated with Magcubic. If your unit is on different firmware, check
[MANUAL.md](MANUAL.md) for what to verify before assuming an exact match, and contributions
documenting other firmware revisions are welcome.
