#!/usr/bin/env python3
"""Build Simple Ad Blocker's built-in blocklist: merge every source list, drop the allowlist, and pack the
result as a sorted table of 64-bit FNV-1a hashes (assets/blocklist.bin) that the app binary-searches.

    python3 -I tools/build_blocklist.py            # downloads the sources
    python3 -I tools/build_blocklist.py DIR        # or reads already downloaded files from DIR

A domain blocks itself and every subdomain, so the app checks a name and each of its parents.
The same sources and parsing run on the TV when you press "Update lists" (see Lists.java).
"""
import os
import struct
import sys
import time
import urllib.request

H = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard"
# name -> url. Keep in sync with Lists.SOURCES in the app.
SOURCES = {
    "hagezi-proplus": H + "/pro.plus-onlydomains.txt",        # ads, trackers, telemetry: strict
    "hagezi-popup": H + "/popupads-onlydomains.txt",          # pop-up and redirect ad networks
    "hagezi-xiaomi": H + "/native.xiaomi-onlydomains.txt",    # Xiaomi's own trackers
    "hagezi-dohbypass": H + "/doh-vpn-proxy-bypass-onlydomains.txt",  # stops apps sneaking past the filter
    "hagezi-tif": H + "/tif.mini-onlydomains.txt",  # threat intel feeds: malware C2, phishing, compromised
                                                    # sites, merged from abuse.ch URLhaus, Spamhaus, OpenPhish
                                                    # etc. The "mini" (highest-confidence) tier: the full tif
                                                    # list runs 2M+ domains, too much resident RAM for this
                                                    # device's 1GB after the boot-memory-pressure work already
                                                    # done this session.
    "hagezi-fake": H + "/fake-onlydomains.txt",    # fake/scam shops, fraud and fake-download sites
    "oisd-big": "https://big.oisd.nl/domainswild2",
    "stevenblack": "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    "adguard-dns": "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    "1hosts-lite": "https://raw.githubusercontent.com/badmojr/1Hosts/main/Lite/domains.wildcards",
    "phishing-army": "https://phishing.army/download/phishing_army_blocklist_extended.txt",  # actively
                                                    # maintained phishing-domain feed, widely used in the
                                                    # Pi-hole/cybersecurity community (Firebog's "tick list")
}

# Ours: Xiaomi TV services this box was seen calling (log upload, usage, device keys).
EXTRA = [
    "tv.global.mi.com", "pandora.mi.com", "data.mistat.xiaomi.com", "data.mistat.intl.xiaomi.com",
    "tracking.miui.com", "tracking.intl.miui.com", "sdkconfig.ad.xiaomi.com", "api.ad.xiaomi.com",
    "globalapi.ad.xiaomi.com", "alphonso.tv", "api.alphonso.tv",
]

# Never blocked, even if a list has them: what the box needs to work and stream.
# Keep in sync with Lists.ALLOW in the app.
ALLOW = [
    "localhost", "connectivitycheck.gstatic.com", "clients3.google.com", "time.android.com",
    "android.clients.google.com", "play.googleapis.com", "play-fe.googleapis.com", "www.gstatic.com",
    "googlevideo.com", "ytimg.com", "youtube.com", "youtubei.googleapis.com", "i.ytimg.com",
    "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net",
    "apple.com", "tv.apple.com", "mzstatic.com", "aaplimg.com",
    "github.com", "objects.githubusercontent.com", "raw.githubusercontent.com",
    "f-droid.org", "api.open-meteo.com", "geocoding-api.open-meteo.com",
    "big.oisd.nl", "adguardteam.github.io",
]


def fnv64(s):
    h = 0xcbf29ce484222325
    for b in s.encode("ascii", "ignore"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h


def parse(text):
    """Accepts plain domains, wildcards (*.x / .x), hosts files and adblock '||x^' rules."""
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in "#!" or line.startswith("@@"):
            continue
        if line.startswith("||"):
            line = line[2:]
            cut = line.find("^")
            if cut < 0 or "/" in line or "*" in line[:cut]:
                continue
            line = line[:cut]
        else:
            parts = line.split()
            if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1", "::", "::1"):
                line = parts[1]
            elif len(parts) != 1:
                continue
            line = line.lstrip("*").lstrip(".")
        line = line.lower().rstrip(".")
        if "." not in line or len(line) > 253 or line in ("localhost.localdomain", "local", "broadcasthost"):
            continue
        if all(c.isalnum() or c in "-._" for c in line):
            out.append(line)
    return out


def main():
    src_dir = sys.argv[1] if len(sys.argv) > 1 else None
    domains = set(EXTRA)
    for name, url in SOURCES.items():
        if src_dir:
            with open(os.path.join(src_dir, name), encoding="utf-8", errors="ignore") as f:
                text = f.read()
        else:
            with urllib.request.urlopen(url, timeout=120) as r:
                text = r.read().decode("utf-8", "ignore")
        got = parse(text)
        print(f"{name:18} {len(got):>8} domains")
        domains.update(got)
    allow = set(ALLOW)
    domains = {d for d in domains if d not in allow}
    hashes = sorted({fnv64(d) for d in domains})
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "assets", "blocklist.bin")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(b"LSB1")
        f.write(struct.pack(">iq", len(hashes), int(time.time())))
        f.write(b"".join(struct.pack(">Q", h) for h in hashes))
    print(f"total unique       {len(hashes):>8}  ->  {out} ({os.path.getsize(out) // 1024} KB)")


if __name__ == "__main__":
    main()
