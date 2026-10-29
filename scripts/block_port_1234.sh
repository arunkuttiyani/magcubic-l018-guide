#!/system/bin/sh
# Blocks an unauthenticated debug console found on TCP port 1234 on this firmware (a
# "CF-Vision" diagnostic service, no login required, reachable from anything on the LAN).
# Installed via Magisk service.d — runs on every boot after Magisk starts services.
iptables -I INPUT -p tcp --dport 1234 -j DROP
ip6tables -I INPUT -p tcp --dport 1234 -j DROP
