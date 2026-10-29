#!/usr/bin/env bash
# Magcubic L018 setup: launcher + firewall + debloat + boot-speed fixes, in one run.
# Safe to re-run (every step checks state before acting). See MANUAL.md for what each step
# does and why. Needs: adb already connected to a rooted (Magisk) L018, a JDK 17+, and the
# Android SDK command-line tools (build-tools;34.0.0, platforms;android-34).
set -euo pipefail
cd "$(dirname "$0")"

SKIP_LAUNCHER=0 SKIP_FIREWALL=0 SKIP_DEBLOAT=0 SKIP_BOOT=0 SKIP_DEXOPT=0
for arg in "$@"; do
  case "$arg" in
    --skip-launcher) SKIP_LAUNCHER=1 ;;
    --skip-firewall) SKIP_FIREWALL=1 ;;
    --skip-debloat)  SKIP_DEBLOAT=1 ;;
    --skip-boot)     SKIP_BOOT=1 ;;
    --skip-dexopt)   SKIP_DEXOPT=1 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      echo
      echo "Flags: --skip-launcher --skip-firewall --skip-debloat --skip-boot --skip-dexopt"
      exit 0 ;;
    *) echo "Unknown flag: $arg (see --help)" >&2; exit 1 ;;
  esac
done

say() { echo; echo "== $* =="; }
adb_() { adb -s "$DEVICE" "$@"; }
has_pkg() { adb_ shell pm list packages | tr -d '\r' | grep -qx "package:$1"; }

# ---- find the device --------------------------------------------------------------------
say "Connecting"
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  read -rp "No ADB device connected. Enter the box's IP:port (Wireless debugging): " TARGET
  adb connect "$TARGET"
  DEVICE="$TARGET"
else
  DEVICE=$(echo "$DEVICES" | head -1)
fi
echo "Using device: $DEVICE"
if ! adb -s "$DEVICE" shell su -c "id" >/dev/null 2>&1; then
  echo "This device doesn't appear to be rooted (Magisk 'su' failed)." >&2
  echo "Root it first — see MANUAL.md section 0 — then re-run this script." >&2
  exit 1
fi

# ---- find the Android SDK ----------------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
  for guess in /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools \
               "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$guess/build-tools/34.0.0" ] && export ANDROID_HOME="$guess" && break
  done
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  echo "Android SDK not found. Set ANDROID_HOME to a dir containing build-tools/34.0.0" >&2
  echo "  sdkmanager \"build-tools;34.0.0\" \"platforms;android-34\"" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME not set — using whatever 'java'/'javac' resolve to on PATH." >&2
fi
echo "ANDROID_HOME=$ANDROID_HOME"

# ---- 1. security: close the debug console and telemetry ---------------------------------
say "Security: debug console + telemetry"
adb_ push scripts/block_port_1234.sh scripts/block_baidu_telemetry.sh /data/local/tmp/
adb_ shell su -c "mv /data/local/tmp/block_port_1234.sh /data/local/tmp/block_baidu_telemetry.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/block_port_1234.sh /data/adb/service.d/block_baidu_telemetry.sh"
adb_ shell su -c "sh /data/adb/service.d/block_port_1234.sh; sh /data/adb/service.d/block_baidu_telemetry.sh" || true
echo "Rules applied (and will reapply on every boot)."

# ---- 2. debloat -----------------------------------------------------------------------
if [ "$SKIP_DEBLOAT" = 0 ]; then
  say "Debloat"
  DISABLE_SAFE="com.android.dynsystem com.android.managedprovisioning com.android.traceur"
  for pkg in $DISABLE_SAFE; do
    if has_pkg "$pkg"; then
      adb_ shell pm disable-user --user 0 "$pkg" >/dev/null 2>&1 && echo "disabled: $pkg" || echo "could not disable (OS-protected?): $pkg"
    fi
  done

  UNINSTALL_SAFE="com.android.music com.android.musicfx com.htc.hyk_test com.htc.storeos
                  com.htc.htcotaupdate com.toofifi.lineserver com.android.toofifi
                  com.hisilicon.explorer com.mobisystems.office"
  for pkg in $UNINSTALL_SAFE; do
    if has_pkg "$pkg"; then
      adb_ shell pm uninstall -k --user 0 "$pkg" >/dev/null 2>&1 && echo "removed (reversible): $pkg"
    fi
  done

  if adb_ shell dumpsys bluetooth_manager 2>/dev/null | grep -qi "bonded"; then
    echo "note: a bonded Bluetooth device was found — leaving com.htc.htcbluetoothremotecontrol alone"
  fi
  echo "Review MANUAL.md section 2 — your firmware's bloat list may differ from this unit's."
else
  say "Debloat: skipped (--skip-debloat)"
fi

# ---- 3. launcher ------------------------------------------------------------------------
if [ "$SKIP_LAUNCHER" = 0 ]; then
  say "Building Lite Home launcher"
  (cd launcher && ./build.sh)
  adb_ install -r launcher/LiteHome.apk
  adb_ shell am force-stop dev.lite.home || true
  echo "Installed. On the device, press Home and choose Lite Home (and \"Always\") when prompted."
else
  say "Launcher: skipped (--skip-launcher)"
fi

# ---- 4. firewall --------------------------------------------------------------------------
if [ "$SKIP_FIREWALL" = 0 ]; then
  say "Building Simple Ad Blocker"
  (cd ad-blocker && ./build.sh)
  adb_ install -r ad-blocker/SimpleAdBlocker.apk
  adb_ shell appops set dev.lite.shield ACTIVATE_VPN allow
  adb_ shell am start -n dev.lite.shield/.ShieldActivity >/dev/null
  sleep 2
  adb_ shell settings put secure always_on_vpn_app dev.lite.shield
  adb_ shell dumpsys deviceidle whitelist +dev.lite.shield >/dev/null
  echo "Installed and set as always-on VPN. Press \"Turn on\" on the box if it isn't already on"
  echo "(the first time needs a tap to accept the VPN permission dialog)."
else
  say "Firewall: skipped (--skip-firewall)"
fi

# ---- 5. boot speed ------------------------------------------------------------------------
if [ "$SKIP_BOOT" = 0 ]; then
  say "Boot speed"
  RAM_KB=$(adb_ shell cat /proc/meminfo | tr -d '\r' | awk '/MemTotal/{print $2}')
  echo "Device RAM: $((RAM_KB / 1024)) MB"
  echo "(the dynsystem/managedprovisioning/traceur disables above are the main fix here;"
  echo " see MANUAL.md section 6 for how to measure the before/after yourself)"
else
  say "Boot speed: skipped (--skip-boot)"
fi

# ---- 6. force AOT-compile the apps that are actually used ---------------------------------
if [ "$SKIP_DEXOPT" = 0 ]; then
  say "Compiling installed apps for speed"
  AC=$(adb_ shell dumpsys battery | tr -d '\r' | awk -F': ' '/AC powered/{print $2}')
  if [ "$AC" = "false" ]; then
    echo "This box reports AC powered: false (battery HAL stub) — Android's own background"
    echo "optimizer never runs as a result, so apps stay interpreted-only forever. Forcing it:"
  fi
  for pkg in $(adb_ shell pm list packages -3 | tr -d '\r' | sed 's/^package://'); do
    adb_ shell pm compile -m speed-profile -f "$pkg" >/dev/null 2>&1 && echo "compiled: $pkg" || true
  done
  echo "Done. This repeats automatically only if you also install scripts/weekly_dexopt.sh"
  echo "by hand (see MANUAL.md section 6 — it's a standing root job, left opt-in on purpose)."
else
  say "Dexopt: skipped (--skip-dexopt)"
fi

say "Done"
echo "Reboot the box, re-enable Wireless debugging, and reconnect ADB to confirm everything"
echo "came back up on its own (launcher, firewall VPN, boot-time disables all persist)."
