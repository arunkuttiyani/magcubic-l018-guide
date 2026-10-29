# Magcubic L018 — the complete guide

Everything, start to finish, to turn a stock Magcubic L018 into a clean, fast, ad-and-telemetry-free
media projector: rooting, a security audit, debloat, a proper launcher, a whole-device DNS firewall,
and real boot/app-speed fixes. Written so you can follow it with **zero prior Android-rooting
experience** — every command says what it does and what you should see back.

This was built and verified end-to-end on one real L018 (Android 14, build `UP1A.231105.001.A1`,
firmware fingerprint `Allwinner/h726_p1/h726-p1`). Read **[Before you start](#before-you-start)**
below first — other units sold under the same "L018" name have turned out to be meaningfully
different hardware, and getting that wrong during rooting is the one step that can actually hurt
your device.

## Table of contents

1. [Before you start](#before-you-start) — know your exact hardware first
2. [What you'll end up with](#what-youll-end-up-with)
3. [Part 0 — Tools you need on your computer](#part-0--tools-you-need-on-your-computer)
4. [Part 1 — Turn on ADB access](#part-1--turn-on-adb-access)
5. [Part 2 — Root with Magisk](#part-2--root-with-magisk)
6. [Part 3 — Security audit: what's actually on this firmware](#part-3--security-audit-whats-actually-on-this-firmware)
7. [Part 4 — Debloat](#part-4--debloat)
8. [Part 5 — Lite Home: the launcher](#part-5--lite-home-the-launcher)
9. [Part 6 — Simple Ad Blocker: the firewall](#part-6--simple-ad-blocker-the-firewall)
10. [Part 7 — Boot and app-launch speed](#part-7--boot-and-app-launch-speed)
11. [Part 8 — Do it all at once: setup.sh](#part-8--do-it-all-at-once-setupsh)
12. [Troubleshooting / FAQ](#troubleshooting--faq)
13. [Undoing any of this](#undoing-any-of-this)
14. [Credits and further reading](#credits-and-further-reading)

---

## Before you start

**Confirm your own hardware before touching anything.** "L018" has been sold with at least two
different real chips under the hood, regardless of what the box or AliExpress listing says:

```bash
adb shell getprop ro.boot.hardware        # platform codename, e.g. sun50iw15p1
adb shell getprop ro.product.cpu.abi      # instruction set, e.g. armeabi-v7a
adb shell getprop ro.bootimage.build.fingerprint
```

On the unit this guide was built on, those come back `sun50iw15p1`, `armeabi-v7a`, and a
fingerprint containing `h726` — a genuinely Allwinner-H726-based unit, running a 32-bit kernel
despite the SoC being 64-bit-capable. Independent research on the same Hotack OEM platform
(see [Credits](#credits-and-further-reading)) found other L018 units that are actually
**Allwinner H713** (`sun50iw12p1`) — also 32-bit. **Both exist.** The architecture you tell
Magisk's patcher (§Part 2) must match your own `ro.product.cpu.abi`, not what this guide or any
box label says — get this wrong and you risk a brick.

If any command in this guide returns something unexpected for your unit, stop and compare before
continuing rather than assuming it'll be fine.

---

## What you'll end up with

- Root access via Magisk, so you can actually control the device instead of just using it
- A list of what this firmware actually does in the background — including, on some units,
  real malware (not just "telemetry") — and how to confirm what's on yours
- The bloat/malware removed or disabled, reversibly
- **Lite Home** — a clean, fast, Apple-TV-style launcher with no ads, no bloat, dedicated
  Inputs/Projector/Wi-Fi/Settings controls
- **Simple Ad Blocker** — a whole-device DNS firewall: ~670,000 domains covering ads, trackers,
  phishing and malware, blocked for every app, not just a browser
- A noticeably faster boot and faster app-opening, with the actual root causes explained

---

## Part 0 — Tools you need on your computer

- **`adb`** (Android Debug Bridge) — part of the Android SDK platform-tools. On macOS:
  `brew install android-platform-tools`. On Windows/Linux, grab
  [platform-tools](https://developer.android.com/tools/releases/platform-tools) directly.
- **A JDK, version 17 or newer** — `brew install openjdk@21` (macOS) or your distro's package
  manager. Confirm with `javac -version`.
- **Android SDK command-line tools**, specifically `build-tools;34.0.0` and
  `platforms;android-34` — needed only to build the two apps in Parts 5–6:
  ```bash
  brew install --cask android-commandlinetools   # macOS; or download from
                                                   # developer.android.com/studio#command-tools
  sdkmanager "build-tools;34.0.0" "platforms;android-34"
  ```
  Then `export ANDROID_HOME=/path/to/the/sdk` (the install prints this path).
- **Python 3.6+** — already on macOS/most Linux; used once, to build the ad-blocker's domain
  list from public sources.
- **A USB-A to USB-A cable** — only needed if something goes wrong during rooting and you need
  FEL-mode recovery (Part 2, Option C). Not needed for the normal path.

---

## Part 1 — Turn on ADB access

1. On the projector: **Settings → About → Build Number**, click it **7 times** — a toast
   confirms "You are now a developer."
2. **Settings → System → Developer options**, turn on:
   - **USB debugging** (this is what actually enables network ADB on this firmware, despite
     the name)
   - **Wireless debugging**, if shown as a separate toggle
3. Open **Wireless debugging** to see the device's IP and port, then on your computer:
   ```bash
   adb pair 192.168.x.x:PAIRING_PORT   # a separate pairing code/port shown on the same screen
   # enter the 6-digit code shown on the TV when prompted
   adb connect 192.168.x.x:5555
   adb devices                          # should list your device as "device", not "unauthorized"
   ```

**Known quirk on this hardware:** Wireless debugging seems to turn itself off on every reboot.
After any reboot, re-enable it on the device and `adb connect` again before running anything
else — this will come up constantly throughout this guide, not just once.

---

## Part 2 — Root with Magisk

This firmware uses Allwinner's **IMAGEWTY** image format, not the standard Android A/B or
`fastboot flash` flow most rooting guides assume — don't follow a generic Android rooting guide
here, it won't match.

**General shape of the process** (see [Credits](#credits-and-further-reading) for the detailed,
actively-maintained toolkit this is based on — read it in full before doing this, it covers
flashing-tool quirks this summary doesn't):

1. **Get your exact firmware image** (`update.img`) for your specific build — from the vendor,
   or from community archives (4PDA, XDA threads for this device family — linked in Credits).
   Using someone else's firmware version on your unit risks a mismatch; prefer a dump of your
   own device's current firmware if you can get one.
2. **Extract the boot partition** from that image (tools exist specifically for Allwinner's
   IMAGEWTY format — a generic image editor won't understand it).
3. **Patch the boot image with Magisk** — use the Magisk app itself, or a web-based patcher.
   Set the **architecture to match what you confirmed in [Before you start](#before-you-start)**
   (`armeabi-v7a` for both known L018 variants, but verify your own), and enable **Patch VBMeta
   Flag**.
4. **Repack the firmware** with the patched boot partition swapping in for the original — the
   rest of the image must be byte-for-byte identical to your dump, with the partition checksums
   recalculated (Allwinner firmware verifies these; get it wrong and flashing fails cleanly at
   that partition rather than corrupting anything — annoying, not dangerous).
5. **Flash it back.** Three ways, easiest first: a FAT32 USB stick with a specific
   `auto_update.txt` trigger file (works even if the device still boots), a local-update menu
   inside the stock Settings app if ADB still works, or PhoenixUSBPro over a USB-A-to-USB-A
   cable in FEL mode (for a device that won't boot at all — this is the real recovery path, not
   just an alternative).
6. **Verify.** Install the Magisk app, confirm it reports root. If it says "needs reinstall,"
   use its own **Install → Direct Install** and reboot once more.

**If this goes wrong:** Magisk's own bootloop protection counts failed boots and automatically
disables the most recently added module after a few — this is what saved the device this guide
was built on, after an unrelated mistake later on (see §Part 3's note on what *not* to do). A
genuinely bricked device (won't boot the bootloader itself) needs Option C above — FEL mode
recovery with a USB-A-to-USB-A cable and your original stock firmware.

**This part is the one place in this guide you should go read the primary source in full**
rather than working from a summary — the exact tool invocations, driver installation, and
flashing-tool version quirks matter and change over time. Start at the toolkit linked in
[Credits](#credits-and-further-reading).

---

## Part 3 — Security audit: what's actually on this firmware

Don't skip this — check what you're dealing with before debloating blind.

### What we found and confirmed on this unit

- **An unauthenticated debug console on TCP port 1234** (a "CF-Vision" diagnostic service) —
  reachable from anything on the same LAN, no login required.
- **Telemetry to Baidu's Mobile Stat SDK**, from the stock launcher (`LuminaOS`), to
  `hmma.baidu.com` (103.235.46.195) and `dxp.baidu.com` (39.156.66.180).

Both are closed by blocking them at the network layer with `iptables`, **not** by patching the
APKs that call them — those APKs share Android's `system` UID with other core processes, and
resigning or directly modifying a shared-UID system APK is a real brick risk (see the note at
the end of this section). Blocking network destinations is reversible and can't touch boot.

```bash
adb push scripts/block_port_1234.sh scripts/block_baidu_telemetry.sh /data/local/tmp/
adb shell su -c "mv /data/local/tmp/block_port_1234.sh /data/local/tmp/block_baidu_telemetry.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/block_port_1234.sh /data/adb/service.d/block_baidu_telemetry.sh"
adb shell su -c "sh /data/adb/service.d/block_port_1234.sh; sh /data/adb/service.d/block_baidu_telemetry.sh"   # apply now, don't wait for reboot
```

### Check for these too — found by other researchers on the same OEM platform

This device's OEM (Hotack) has shipped **genuine malware**, not just telemetry, on some units in
this family. Check whether these are on yours:

```bash
adb shell pm list packages | grep -E "silentsdk|expandsdk|eventuploadservice|hyk_test|rockchip|huawei"
```

| Package | What it does |
|---|---|
| `com.hotack.silentsdk` | A RAT/dropper — contacts a remote server and downloads a second-stage payload. **Not confirmed on every unit**, but has been found on this exact device family. |
| `com.htc.expandsdk` | Ad-injection and persistence malware, found specifically on L018 units. |
| `com.htc.eventuploadservice` | Requests camera, microphone, contacts, location and package-install permissions for a "telemetry" service — a remarkably broad permission set for what it claims to be. |
| `android.rockchip.update.service` | An updater for a *different* SoC vendor's chips, present on this Allwinner-based hardware — worth investigating if you find it. |
| `com.huawei.connection` | A Huawei service with no obvious reason to be on Allwinner hardware. |

If you find `com.hotack.silentsdk` or `com.htc.expandsdk` specifically, treat it as malware, not
bloat — remove it, and consider checking your router's DNS/firewall logs for anything reaching
out to unfamiliar domains before and after removal.

Also worth checking on your own unit: whether `/system/bin/appsdisable` (a boot script some
units use to silently disable Google Play Protect on first boot) and `/*/preinstall/` directories
(silently sideloaded APKs at boot) exist and contain anything unexpected —
`find /*/preinstall -name "*.apk"` after rooting.

### What *not* to do — a real near-brick

Early in building this guide, we tried to directly patch/resign a shared-UID (`android.uid.system`)
system APK (the stock launcher) with a locally-generated debug key, to change its behavior
directly rather than working around it. This broke Android's signature matching for every
process sharing that UID and the device hung for an extremely long time on boot. It recovered on
its own — Magisk's bootloop protection disabled the module automatically after enough failed
boots — and we removed the module entirely once recovered.

**The lesson: never resign or directly patch a system APK that declares `sharedUserId`.** Block
it at the network layer (`iptables`, above) or hide/disable it (next section) instead.

---

## Part 4 — Debloat

Everything here is `pm disable-user` or `pm uninstall -k --user 0` — both **fully reversible**
(`pm enable`, or reinstalling from the Play Store, respectively) and neither touches the system
partition.

```bash
# Dynamic System Updates (GSI testing, unused) / enterprise MDM provisioning / system tracing
adb shell pm disable-user --user 0 com.android.dynsystem
adb shell pm disable-user --user 0 com.android.managedprovisioning
adb shell pm disable-user --user 0 com.android.traceur
# com.android.devicelockcontroller is OS-protected and will refuse to disable even as root — leave it

# Bundled apps unlikely to be useful to you (check your own package list first:
# adb shell pm list packages -3 — yours may differ)
adb shell pm uninstall -k --user 0 com.android.music        # stock music player, redundant
adb shell pm uninstall -k --user 0 com.android.musicfx       # audio effects for the above
adb shell pm uninstall -k --user 0 com.htc.hyk_test          # factory test app, 44MB, debug bloat
adb shell pm uninstall -k --user 0 com.htc.storeos           # unverified secondary app store
adb shell pm uninstall -k --user 0 com.htc.htcotaupdate      # OTA updater — also stops an update
                                                               # silently overwriting your root
adb shell pm uninstall -k --user 0 com.toofifi.lineserver    # casting service, phones home
adb shell pm uninstall -k --user 0 com.android.toofifi       # paired with the above
adb shell pm uninstall -k --user 0 com.hisilicon.explorer    # bundled demo app
adb shell pm uninstall -k --user 0 com.mobisystems.office    # bundled office-suite trial
```

**Keep `com.htc.htcbluetoothremotecontrol`** if your remote pairs over Bluetooth — check first:

```bash
adb shell dumpsys bluetooth_manager | grep -i bonded
```

If that shows a bonded remote, disabling this package leaves you with no way to control the box.

---

## Part 5 — Lite Home: the launcher

A hand-rolled, plain-Java (no Gradle) Android TV launcher: a horizontal app dock/grid, round
pills (Wi-Fi, Inputs, Projector settings, Settings) that expand into labelled pills on D-pad
focus, weather, and hold-OK-to-reorder/hide for apps.

```bash
cd launcher
./build.sh          # needs ANDROID_HOME and JAVA_HOME set, or discoverable at common paths
adb install -r LiteHome.apk
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
# choose Lite Home, and "Always", when Android asks which launcher to use
```

You should see: a clock/date/weather header top-left, four round icon buttons top-right
(Wi-Fi, Inputs, Projector, Settings), and your installed apps in a horizontal dock. Press and
hold OK on any app tile for a menu (Hide / App info / Uninstall / Move); hold OK on the Wi-Fi
pill for launcher Settings.

**The Inputs and Projector pills are wired to this firmware's own component names**
(`com.softwinner.awsource` for input switching, and this device's settings app for projector
controls — see `startInputs()`/`startProjector()` in `HomeActivity.java`). If your unit reports
a different `ro.product.model`, these may need adjusting — find the right component with:

```bash
adb shell cmd package query-activities --brief -a android.intent.action.MAIN
# ...while pressing your stock remote's Input/Source button, to see what it launches
```

---

## Part 6 — Simple Ad Blocker: the firewall

A local VPN that intercepts **only DNS** — video and downloads never touch it. A blocked domain
resolves to `0.0.0.0`; everything else goes to your router's own DNS and the answer is passed
straight back.

**~670,000 domains**, merged from HaGeZi (Pro++, pop-up ads, Xiaomi trackers, DoH/VPN-bypass,
threat-intel, fake shops), OISD Big, StevenBlack, AdGuard DNS, 1Hosts Lite and Phishing Army —
real cybersecurity coverage (malware command-and-control, phishing, scam sites), not just ad
networks.

```bash
cd ad-blocker
./build.sh            # downloads and merges the source lists on first run (a few minutes)
adb install -r SimpleAdBlocker.apk
adb shell appops set dev.lite.shield ACTIVATE_VPN allow   # skips the on-screen VPN consent dialog
adb shell am start -n dev.lite.shield/.ShieldActivity
# press "Turn on"
adb shell settings put secure always_on_vpn_app dev.lite.shield   # survives reboot on its own
adb shell dumpsys deviceidle whitelist +dev.lite.shield             # exempt from battery/doze kill
```

You should see, on the app's own screen: "On. Ads, pop-ups, trackers and Xiaomi telemetry are
blocked across the whole box," a domain count, and (after some use) a "Recently blocked" list.
Test it directly:

```bash
adb shell ping -c 1 doubleclick.net   # should NOT reach the real doubleclick IP
adb shell ping -c 1 google.com        # should resolve and respond normally
```

Only one VPN can be active at a time on Android, so this replaces anything else (NetGuard, etc.)
you may already have running. **Limits:** YouTube's own in-video ads come from the same servers
as the video itself, so DNS filtering can't touch them (use SmartTube or similar, which strip
them client-side instead).

---

## Part 7 — Boot and app-launch speed

Two separate, unrelated problems, both confirmed and fixed on this unit:

### A process-thrashing storm right after boot

On boot, every installed app's own `BOOT_COMPLETED` receiver tries to start a process at once.
On a 1GB-RAM device (check yours: `adb shell getprop ro.boot.dramsize`), the kernel's
low-memory-killer can't keep up and kills them almost as fast as they spawn, for the better part
of a minute. Disabling the unused packages from Part 4 (`dynsystem`, `managedprovisioning`,
`traceur`) cut the kill/thrash count by roughly 75% on this unit, measured with:

```bash
adb shell logcat -b all -d | grep -c killinfo     # run right after a fresh boot
```

### Apps never getting AOT-compiled

Check whether your actually-used apps are running fully interpreted:

```bash
adb shell dumpsys package <your-package-name> | grep status=
```

If you see `status=verify` instead of `status=speed-profile`, the app has never been optimized —
noticeably slower to open, choppier under load. This happens because Android's background
optimizer requires an idle **and charging** window to run, and on this hardware
`adb shell dumpsys battery` reports `AC powered: false` even though the device is mains-powered
(the battery HAL is a stub that never reports a charge source). That window never opens here, so
apps stay uncompiled indefinitely unless you force it:

```bash
for pkg in $(adb shell pm list packages -3 | sed 's/^package://' | tr -d '\r'); do
  adb shell pm compile -m speed-profile -f "$pkg"
done
```

This is a one-time fix per app install — it survives reboots, and only reverts to `verify` the
next time that specific app updates. `scripts/weekly_dexopt.sh` is an **optional** Magisk boot
script that redoes this automatically about once a week; it's a standing root-level job running
unattended, so it's not installed by `setup.sh` by default — install it yourself if you want it:

```bash
adb push scripts/weekly_dexopt.sh /data/local/tmp/
adb shell su -c "mv /data/local/tmp/weekly_dexopt.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/weekly_dexopt.sh"
```

### Things worth checking that were already fine on this unit

zRAM swap, CPU/GPU frequency governors, storage write speed, Wi-Fi signal/link speed, and UI
animation scale were all already reasonable on this device — worth a quick look on yours in case
your firmware differs, but none of them needed changing here. See
`adb shell cat /proc/swaps`, `adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor`
(needs `su -c`), and `adb shell settings get global window_animation_scale`.

---

## Part 8 — Do it all at once: setup.sh

Once you've rooted (Part 2) and connected ADB (Part 1), `setup.sh` runs Parts 3–7 in order,
idempotently (safe to re-run), with minimal prompts:

```bash
./setup.sh                     # everything, in order
./setup.sh --skip-firewall     # launcher + debloat + boot fixes only
./setup.sh --help              # see all flags
```

It does **not** install `weekly_dexopt.sh` (end of Part 7) — that one's a standing root job and
is an intentional manual opt-in.

---

## Troubleshooting / FAQ

**ADB keeps disconnecting / "device offline."** Known quirk on this hardware: Wireless debugging
turns itself off on every reboot. Re-enable it on the device (Part 1) and `adb connect` again.

**`adb shell su -c "..."` says `permission denied` or just hangs.** Magisk needs to grant your
ADB shell root access the first time — check the Magisk app's own "Superuser" log/request list
on the device if a prompt doesn't appear automatically.

**The build script fails with "Android SDK not found."** Set `ANDROID_HOME` to the directory
containing `build-tools/34.0.0` (see Part 0).

**The build script fails with an SSL/certificate error fetching the blocklists.** This is a
Python certificate-bundle issue on your computer, not the device — on macOS, run
`/Applications/Python 3.x/Install Certificates.command`.

**I set up the ad-blocker and now nothing on the device can reach the internet.** Check
`adb shell dumpsys connectivity | grep -i "VPN CONNECTED"` — if it's missing, the VPN isn't
actually up; re-open the app and press "Turn on" again. If it IS up but nothing resolves, your
router's own DNS server may be unreachable from the device — the firewall forwards to it and has
nowhere to go if it's down.

**My unit reports a different chip / Android version / package names than this guide.** See
[Before you start](#before-you-start) — this is a known issue across the "L018" product line.
Verify each assumption against your own `getprop` output rather than copying commands blind,
and consider opening an issue or PR here documenting what's different on your unit.

---

## Undoing any of this

- **Debloat:** `pm enable <package>` for anything disabled; reinstall from the Play Store for
  anything removed with `-k` (data was kept).
- **Firewall:** press "Turn off" in the app, then
  `adb shell settings delete secure always_on_vpn_app` and uninstall.
- **Launcher:** install and switch to any other launcher via Settings → Apps → Default apps →
  Home app.
- **Network blocks:** `adb shell su -c "rm /data/adb/service.d/block_port_1234.sh
  /data/adb/service.d/block_baidu_telemetry.sh"`, then reboot (or re-run the `iptables -D`
  equivalent of each rule immediately).
- **Root itself:** reflash your original stock firmware image via the same method used in Part 2.

---

## Credits and further reading

- **[well0nez/magcubic-root](https://github.com/well0nez/magcubic-root)** — the detailed,
  actively-maintained root/flash toolkit this guide's Part 2 and the malware findings in Part 3
  are based on. **Read this in full before rooting** — it has the exact tool invocations and
  flashing-tool quirks that change over time.
- Blocklists: [HaGeZi](https://github.com/hagezi/dns-blocklists), [OISD](https://oisd.nl/),
  [StevenBlack](https://github.com/StevenBlack/hosts),
  [AdGuard](https://github.com/AdguardTeam/AdGuardSDNSFilter),
  [1Hosts](https://github.com/badmojr/1Hosts), [Phishing Army](https://phishing.army/)
- Rooting tool: [Magisk](https://github.com/topjohnwu/Magisk)

This is a community guide, not affiliated with Magcubic or Hotack. Everything here was
reverse-engineered and tested against real units — contributions documenting other firmware
revisions or hardware variants are welcome.
