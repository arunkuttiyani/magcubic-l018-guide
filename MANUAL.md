# Magcubic L018 — debloat, launcher, firewall and performance guide

A complete, from-scratch walkthrough for the **Magcubic L018** projector (Allwinner H726,
Android 14, `UP1A.231105.001.A1`). Everything here was built and verified on a real L018. It
should also work, unmodified or with small tweaks, on other Allwinner-H726-based Magcubic/clone
projectors running a similar Android 14 build — check `getprop ro.product.model` and
`getprop ro.build.fingerprint` on your unit before assuming an exact match.

**What this gets you:**
- A clean, fast, Apple-TV-style launcher (no bloat, no ads, no telemetry) with dedicated
  Inputs / Projector-settings / Wi-Fi / Settings controls
- A whole-device DNS ad- and tracker-blocker, including real phishing/malware threat-intel lists
- Several confirmed telemetry endpoints and an unauthenticated debug console closed off
- A noticeably faster boot and faster app-opening

**What you need first:**
- Root via Magisk (see [Prerequisites](#0-prerequisites) below if you haven't done this yet)
- ADB access to the box (wireless debugging, or a USB-C/OTG adapter)
- A computer with `adb`, a JDK (17+), and the Android SDK command-line tools

Run `setup.sh` from a computer with ADB access to the box to do all of this automatically
(see [Automated setup](#automated-setup) at the bottom) — the steps below are what it does,
explained, for anyone who wants to understand or do it by hand.

---

## 0. Prerequisites

This guide assumes the box is **already rooted with Magisk**. Rooting itself is device- and
firmware-specific and out of scope here — the usual path is: get the stock firmware/boot image
for your exact build, patch `boot.img` with the Magisk app, flash the patched image back
(fastboot or the vendor's flash tool), confirm root with the Magisk app. **Get this exactly
right for your own firmware version before going further** — a mismatched patch can hang on
boot. If anything goes wrong, Magisk's own bootloop protection auto-disables the last change
after a few failed boots, which gets you back to a bootable state.

Enable **Settings → System → Developer options → Wireless debugging**, note the IP and port
shown, and on your computer:

```bash
adb pair 192.168.x.x:PAIRING_PORT   # enter the 6-digit code shown on the TV
adb connect 192.168.x.x:5555
```

**Known quirk on this hardware:** wireless debugging seems to turn itself off on every reboot.
After any reboot you'll need to re-enable it on the device and `adb connect` again before
re-running anything here.

---

## 1. Security/privacy audit — what to close off

Before changing anything else, it's worth knowing what the stock firmware actually does. On
this unit we found:

- **An unauthenticated debug console on TCP port 1234** (a "CF-Vision" diagnostic service) —
  reachable from anything on the same LAN, no login. We block inbound connections to it rather
  than touching the service itself (safer — the service is part of a `system`-UID process, and
  resigning or patching shared-UID system APKs is a real brick risk; **don't do that**).
- **LuminaOS's Baidu Mobile Stat SDK** phoning telemetry to `hmma.baidu.com` /
  `dxp.baidu.com`. Same reasoning: block the two destination IPs rather than touch the APK.

Both are closed with a couple of `iptables` rules, installed as Magisk boot scripts so they
reapply on every boot (`scripts/block_port_1234.sh`, `scripts/block_baidu_telemetry.sh`).

```bash
adb push scripts/block_port_1234.sh scripts/block_baidu_telemetry.sh /data/local/tmp/
adb shell su -c "mv /data/local/tmp/block_port_1234.sh /data/local/tmp/block_baidu_telemetry.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/block_port_1234.sh /data/adb/service.d/block_baidu_telemetry.sh"
adb shell su -c "sh /data/adb/service.d/block_port_1234.sh && sh /data/adb/service.d/block_baidu_telemetry.sh"   # apply immediately, don't wait for reboot
```

**If your box phones home to different IPs**, find them with `tcpdump`/`logcat` on a fresh boot
and adjust the scripts accordingly — don't just copy these IPs blind if you haven't confirmed
your own firmware calls the same services.

---

## 2. Debloat — what's safe to disable

Everything here is `pm disable-user` or `pm uninstall -k --user 0`, both **fully reversible**
(`pm enable` / reinstalling from Play Store respectively) and neither touches system partitions.
We specifically avoided anything that risks boot (see §5 for why resigning shared-UID system
APKs is dangerous — we hit this ourselves early on and had to let Magisk's bootloop protection
recover the device).

```bash
adb shell pm disable-user --user 0 com.android.dynsystem          # Dynamic System Updates (GSI testing, unused)
adb shell pm disable-user --user 0 com.android.managedprovisioning # enterprise/MDM provisioning, unused
adb shell pm disable-user --user 0 com.android.traceur             # system tracing tool, pure debug bloat
# com.android.devicelockcontroller is OS-protected and will refuse to disable even as root — leave it
```

Packages removed in the original audit on this unit (your package list may differ — check
`adb shell pm list packages -3` first):

```bash
adb shell pm uninstall -k --user 0 com.android.music       # stock music player, redundant
adb shell pm uninstall -k --user 0 com.android.musicfx      # audio effects for the above
adb shell pm uninstall -k --user 0 com.htc.hyk_test         # HTC diagnostic/test app
adb shell pm uninstall -k --user 0 com.htc.storeos          # HTC app store, unused
adb shell pm uninstall -k --user 0 com.htc.htcotaupdate     # OTA updater — disabling avoids an
                                                              # update silently overwriting root
adb shell pm uninstall -k --user 0 com.toofifi.lineserver   # bundled ad/content service
adb shell pm uninstall -k --user 0 com.android.toofifi      # paired with the above
adb shell pm uninstall -k --user 0 com.hisilicon.explorer   # bundled demo app
adb shell pm uninstall -k --user 0 com.mobisystems.office   # bundled office-suite trial
```

**Keep `com.htc.htcbluetoothremotecontrol`** if your remote pairs over Bluetooth (check
`adb shell dumpsys bluetooth_manager | grep -i bonded` for a bonded "Bluetooth remote" device
first) — disabling it on a box whose only remote uses it will leave you with no remote.

---

## 3. Lite Home — the launcher

A hand-rolled, plain-Java (no Gradle) Android TV launcher, Apple-TV-style: a horizontal app
dock/grid, round pills (Wi-Fi, Inputs, Projector, Settings) that expand into labelled pills on
D-pad focus, weather, and a hold-to-reorder/hide system for apps.

```bash
cd launcher
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17+ ./build.sh
adb install -r LiteHome.apk
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
# pick Lite Home and "Always" when Android asks which launcher to use
```

The build needs `build-tools;34.0.0` and `platforms;android-34` from the Android SDK
command-line tools (`sdkmanager "build-tools;34.0.0" "platforms;android-34"`), and a JDK 17+.

**The Inputs and Projector pills are wired to this exact firmware's component names**
(`com.softwinner.awsource`, and this device's own settings activity under
`com.softwinner.tv.*`/system settings) — see `HomeActivity.java` around `startInputs()` /
`startProjector()`. If your box reports a different `ro.product.model` or you're on a different
SoC vendor, these component names will likely need adjusting; `adb shell cmd package
query-activities --brief -a android.intent.action.MAIN` while you press the stock remote's
Input/Source button is the fastest way to find the right one for your unit.

Hiding system/debug apps from the grid (without uninstalling them) is done through the
launcher's own Settings screen (hold OK on the Wi-Fi pill → Show hidden apps), or directly:

```bash
# from the launcher's own long-press menu on any app tile → "Hide"
```

---

## 4. Simple Ad Blocker — the firewall

A local VPN that only intercepts DNS (video/downloads never touch it): ads, pop-ups, trackers,
phishing, malware and Xiaomi/Allwinner telemetry domains resolve to `0.0.0.0`; everything else
is forwarded to your router's own DNS and the answer passed straight back.

**~670,000 domains**, merged from HaGeZi (Pro++, pop-up ads, Xiaomi trackers, DoH/VPN bypass,
threat-intel, fake shops), OISD Big, StevenBlack, AdGuard DNS, 1Hosts Lite and Phishing Army —
including real cybersecurity threat-intel (malware C2, phishing, scam sites), not just ad
networks. Lists refresh themselves weekly; **Update lists** on the app's own screen does it
on demand.

```bash
cd ad-blocker
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk17+ ./build.sh   # downloads the lists
                                                                           # on first run (~5 min)
adb install -r SimpleAdBlocker.apk
adb shell appops set dev.lite.shield ACTIVATE_VPN allow   # skips the on-screen VPN consent dialog
adb shell am start -n dev.lite.shield/.ShieldActivity
# press "Turn on"
adb shell settings put secure always_on_vpn_app dev.lite.shield   # survives reboot on its own
adb shell dumpsys deviceidle whitelist +dev.lite.shield            # exempt from battery/doze kill
```

Only one VPN can be active at a time on Android, so this replaces anything else (NetGuard,
etc.) you may already have running. **Limits:** YouTube's own in-video ads come from the same
servers as the video itself, so DNS filtering can't touch them (use SmartTube or similar, which
strip them client-side instead). Encrypted-DNS-capable apps can in principle bypass any DNS
filter; the DoH/VPN-bypass list blocks the best-known providers' own domains to reduce this.

---

## 5. What *not* to do — a real near-brick, so you don't repeat it

Early in this project we tried to directly patch/resign a shared-UID (`android.uid.system`)
system APK (the stock OEM launcher, LuminaOS) with a locally-generated debug key. This broke
Android's signature matching for every process sharing that UID and the device hung for an
extremely long time on boot. **It recovered on its own** — Magisk's bootloop-protection counts
failed boots and automatically disables the most recently added module after a few — and the
module was then removed entirely.

**The lesson: never resign or directly patch a system APK that declares `sharedUserId`.** If
you want to change behaviour of something like that, block it at the network layer (iptables,
like §1 above) or hide/disable it (§2), never resign it.

---

## 6. Boot speed

Two independent, unrelated problems were found and fixed on this unit:

**A process-thrashing storm right after Magisk/zygote start.** On boot, every installed app's
own `BOOT_COMPLETED` receiver tries to start a process at once. On a 1GB-RAM device (check
yours: `adb shell getprop ro.boot.dramsize`) the kernel's low-memory-killer can't keep up and
kills them almost as fast as they spawn, for the better part of a minute. Disabling the unused
packages in §2 (`dynsystem`, `managedprovisioning`, `traceur`) cut the kill/thrash count by
roughly 75% on this unit (`logcat -b all -d | grep -c killinfo` before/after a reboot is how we
measured it).

**Apps never getting AOT-compiled.** Check `adb shell dumpsys package <pkg> | grep status=` for
your actually-used apps. If they say `status=verify` instead of `status=speed-profile`, they're
running fully interpreted — much slower to open, choppier under load — because Android's
background optimizer requires an idle+**charging** window to run, and on this box
`adb shell dumpsys battery` reports `AC powered: false` even though it's mains-powered (the
battery HAL is a stub that never reports a charge source). That idle-maintenance window
structurally never opens here, so apps just stay uncompiled forever unless you force it:

```bash
for pkg in $(adb shell pm list packages -3 | sed 's/^package://' | tr -d '\r'); do
  adb shell pm compile -m speed-profile -f "$pkg"
done
```

This is a one-time fix per app install — it survives reboots, but reverts to `verify` the next
time that specific app updates. `scripts/weekly_dexopt.sh` is an optional Magisk boot script
that redoes this automatically about once a week; it's not installed by `setup.sh` by default
(see the script's own comment for why — it's a standing root-level boot job, decide for
yourself whether you want that running unattended) — install it by hand if you want it:

```bash
adb push scripts/weekly_dexopt.sh /data/local/tmp/
adb shell su -c "mv /data/local/tmp/weekly_dexopt.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/weekly_dexopt.sh"
```

**Things we checked that were already fine on this unit** (worth checking on yours too, in
case your firmware differs): zRAM swap config, CPU/GPU frequency governors, storage write
speed, Wi-Fi signal/link speed, UI animation scale. See the chat history / commit messages in
this repo for exact numbers if you want to compare against your own box.

---

## Automated setup

`setup.sh` runs everything above in order, idempotently (safe to re-run), with minimal
prompts — it only asks you to confirm the device IP and to physically approve the VPN consent
dialog's `appops` grant if that step is skipped. It assumes: `adb` already connected to the
box, Magisk root already set up, and `ANDROID_HOME`/`JAVA_HOME` already exported (or
discoverable at common install paths — it tries before giving up).

```bash
./setup.sh                     # everything, in order
./setup.sh --skip-firewall     # launcher + debloat + boot fixes only
./setup.sh --help              # see all flags
```

It does **not** install `weekly_dexopt.sh` (§6) by default — that one's a standing root job and
is left as a manual opt-in on purpose.

---

## Credits / sources

Blocklists: [HaGeZi](https://github.com/hagezi/dns-blocklists),
[OISD](https://oisd.nl/), [StevenBlack](https://github.com/StevenBlack/hosts),
[AdGuard](https://github.com/AdguardTeam/AdGuardSDNSFilter),
[1Hosts](https://github.com/badmojr/1Hosts), [Phishing Army](https://phishing.army/).
Rooting: [Magisk](https://github.com/topjohnwu/Magisk).

This is a community guide, not affiliated with Magcubic. Everything here was reverse-engineered
and tested against one real unit — contributions documenting other L018 firmware revisions (or
other Allwinner-H726 clones) are welcome.
