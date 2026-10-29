# Simple Ad Blocker

An on-device DNS filter for the whole box: ads, pop-ups, trackers and Xiaomi telemetry for **every app,
browsers included**. Nothing leaves the box except normal name lookups, which go to your own router.

How it works: a local VPN routes only a pretend DNS server's address, so every app's name lookups arrive
here and nothing else does (video and downloads never touch this code). A blocked name gets `0.0.0.0`;
any other lookup is passed to the router's DNS server and the answer handed back.

- **Lists:** merged from HaGeZi (Pro++, pop-up ads, Xiaomi, DNS-bypass, threat intel, fake shops), OISD
  Big, StevenBlack, AdGuard DNS, 1Hosts Lite and Phishing Army, plus the Xiaomi services this project's
  audit found. The threat-intel and fake-shop lists add real cybersecurity coverage (malware C2, phishing
  and scam sites, aggregated from abuse.ch, Spamhaus, OpenPhish, PhishTank and others), not just ads.
  Hashed into a table; a listed domain blocks its subdomains. A small allowlist keeps the box
  working (connectivity checks, Play, YouTube, Netflix, Apple TV, GitHub, weather).
- **Always on:** foreground service, battery-optimisation exempt, set as the system's Always-on VPN,
  restarted after boot, updates or a crash (a one-minute alarm watches it).
- **Updates:** lists refresh weekly by themselves, or press **Update lists** on its screen.
- **Limits:** YouTube's own ads come from the same servers as its videos, so they can't be blocked by
  name (use SmartTube). Apps that use their own encrypted DNS can bypass any DNS filter; the DNS-bypass
  list blocks the best-known providers.

## Build and install

```bash
ad-blocker/build.sh                          # builds the list on first run, then the APK
adb install -r ad-blocker/SimpleAdBlocker.apk
adb shell appops set dev.lite.shield ACTIVATE_VPN allow   # skips the on-screen VPN consent
adb shell am start -n dev.lite.shield/.ShieldActivity     # then press "Turn on"
# optional, so Android restarts it by itself:
adb shell settings put secure always_on_vpn_app dev.lite.shield
adb shell dumpsys deviceidle whitelist +dev.lite.shield
```

Only one VPN can be active, so turning this on replaces NetGuard. Undo: press **Turn off**, then
`adb shell settings delete secure always_on_vpn_app` and uninstall.
