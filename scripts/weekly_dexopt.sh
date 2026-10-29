#!/system/bin/sh
# This box's battery HAL reports AC-powered=false even though it's mains-powered (it's a
# projector, no battery), so Android's idle+charging-gated background dexopt job never runs:
# every real app stays stuck at the slow "verify" (interpreter-only) compile filter forever.
# This stands in for that job: once a week, after boot has settled, AOT-compile every
# third-party app using its collected runtime profile (same thing bg-dexopt would do).
STAMP=/data/adb/last_dexopt
NOW=$(date +%s)
LAST=$(cat "$STAMP" 2>/dev/null || echo 0)
AGE=$((NOW - LAST))
if [ "$AGE" -lt 604800 ]; then
  exit 0
fi
# give the boot-time process storm (and any app installs in flight) a couple of minutes to settle
sleep 120
for pkg in $(pm list packages -3 | sed 's/^package://'); do
  pm compile -m speed-profile "$pkg" >/dev/null 2>&1
done
echo "$NOW" > "$STAMP"
