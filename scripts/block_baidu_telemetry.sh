#!/system/bin/sh
# Blocks LuminaOS's Baidu Mobile Stat SDK (telemetry/tracking) from reaching its servers.
# LuminaOS shares the system UID (1000) with other processes, so this blocks by destination
# IP (the only two hosts this SDK ever contacts) rather than by UID, to avoid affecting any
# other system component.
#   hmma.baidu.com  103.235.46.195  - tracking beacon (app.gif / auto.gif)
#   dxp.baidu.com   39.156.66.180   - autoTracker / vizParser
# Installed via Magisk service.d — runs on every boot after Magisk starts services.
iptables -I OUTPUT -d 103.235.46.195 -j DROP
iptables -I OUTPUT -d 39.156.66.180 -j DROP
