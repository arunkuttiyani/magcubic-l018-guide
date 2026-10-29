package dev.lite.shield;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The blocklist: about 580,000 ad, pop-up, tracker and telemetry domains merged from several public
 * lists, kept as a sorted array of 64-bit hashes (8 bytes a domain, ~4.5 MB) and binary-searched.
 * A listed domain blocks all its subdomains. The built-in copy ships in assets/blocklist.bin
 * (tools/build_blocklist.py); "Update lists" rebuilds it on the TV from the same sources.
 */
final class Lists {
    private Lists() {}

    private static final String H = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard";
    /** Keep in sync with tools/build_blocklist.py. */
    static final String[][] SOURCES = {
        {"HaGeZi Pro++", H + "/pro.plus-onlydomains.txt"},
        {"HaGeZi pop-up ads", H + "/popupads-onlydomains.txt"},
        {"HaGeZi Xiaomi trackers", H + "/native.xiaomi-onlydomains.txt"},
        {"HaGeZi DNS bypass", H + "/doh-vpn-proxy-bypass-onlydomains.txt"},
        {"HaGeZi threat intel", H + "/tif.mini-onlydomains.txt"},
        {"HaGeZi fake shops", H + "/fake-onlydomains.txt"},
        {"OISD Big", "https://big.oisd.nl/domainswild2"},
        {"StevenBlack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"},
        {"AdGuard DNS", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"},
        {"1Hosts Lite", "https://raw.githubusercontent.com/badmojr/1Hosts/main/Lite/domains.wildcards"},
        {"Phishing Army", "https://phishing.army/download/phishing_army_blocklist_extended.txt"},
    };

    static final String[] EXTRA = {
        "tv.global.mi.com", "pandora.mi.com", "data.mistat.xiaomi.com", "data.mistat.intl.xiaomi.com",
        "tracking.miui.com", "tracking.intl.miui.com", "sdkconfig.ad.xiaomi.com", "api.ad.xiaomi.com",
        "globalapi.ad.xiaomi.com", "alphonso.tv", "api.alphonso.tv",
    };

    /** Never blocked (with their subdomains): what the box needs to work and stream. */
    static final Set<String> ALLOW = new HashSet<>(Arrays.asList(
        "localhost", "connectivitycheck.gstatic.com", "clients3.google.com", "time.android.com",
        "android.clients.google.com", "play.googleapis.com", "play-fe.googleapis.com", "www.gstatic.com",
        "googlevideo.com", "ytimg.com", "youtube.com", "youtubei.googleapis.com", "i.ytimg.com",
        "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net",
        "apple.com", "tv.apple.com", "mzstatic.com", "aaplimg.com",
        "github.com", "objects.githubusercontent.com", "raw.githubusercontent.com",
        "f-droid.org", "api.open-meteo.com", "geocoding-api.open-meteo.com",
        "big.oisd.nl", "adguardteam.github.io"));

    private static volatile long[] table = new long[0];
    private static volatile long builtAt;

    static int size() { return table.length; }
    static long builtAt() { return builtAt; }

    /** True if the name, or any domain above it, is listed (unless an allowed domain comes first). */
    static boolean blocked(String name) {
        long[] t = table;
        String n = name.toLowerCase(Locale.ROOT);
        while (true) {
            if (ALLOW.contains(n)) return false;
            if (Arrays.binarySearch(t, fnv(n)) >= 0) return true;
            int dot = n.indexOf('.');
            if (dot < 0 || n.indexOf('.', dot + 1) < 0) return false; // stop at the registrable domain
            n = n.substring(dot + 1);
        }
    }

    /** Loads the updated copy if there is one, else the built-in one. */
    static synchronized void load(Context c) {
        if (table.length > 0) return;
        File f = file(c);
        try (InputStream in = f.exists() ? new FileInputStream(f) : c.getAssets().open("blocklist.bin")) {
            read(in);
        } catch (IOException e) {
            try (InputStream in = c.getAssets().open("blocklist.bin")) { read(in); } catch (IOException ignored) { }
        }
    }

    private static void read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'L' || magic[1] != 'S' || magic[2] != 'B' || magic[3] != '1') throw new IOException("bad list");
        int n = in.readInt();
        long at = in.readLong();
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = in.readLong();
        table = t;
        builtAt = at * 1000;
    }

    static File file(Context c) { return new File(c.getFilesDir(), "blocklist.bin"); }

    /**
     * Downloads every source again and swaps in the new table. Returns the number of domains, or
     * throws if too few sources answered (the current table is then kept).
     */
    static int update(Context c) throws IOException {
        Growing hashes = new Growing();
        for (String d : EXTRA) hashes.add(fnv(d));
        int ok = 0;
        for (String[] s : SOURCES) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(s[1]).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "SimpleAdBlocker");
                if (conn.getResponseCode() != 200) continue;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String d = parse(line);
                        if (d != null && !ALLOW.contains(d)) hashes.add(fnv(d));
                    }
                }
                ok++;
            } catch (IOException ignored) {
                // one list down is fine; the others still apply
            }
        }
        if (ok < SOURCES.length / 2) throw new IOException("only " + ok + " of " + SOURCES.length + " lists answered");
        long[] t = hashes.sortedUnique();
        File tmp = new File(c.getFilesDir(), "blocklist.tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            out.writeBytes("LSB1");
            out.writeInt(t.length);
            out.writeLong(System.currentTimeMillis() / 1000);
            for (long h : t) out.writeLong(h);
        }
        if (!tmp.renameTo(file(c))) throw new IOException("could not save the list");
        table = t;
        builtAt = System.currentTimeMillis();
        return t.length;
    }

    /** Same rules as tools/build_blocklist.py: plain, wildcard, hosts-file and adblock-style lines. */
    static String parse(String line) {
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!' || line.startsWith("@@")) return null;
        if (line.startsWith("||")) {
            line = line.substring(2);
            int cut = line.indexOf('^');
            if (cut < 0 || line.indexOf('/') >= 0 || line.substring(0, cut).indexOf('*') >= 0) return null;
            line = line.substring(0, cut);
        } else {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1")
                    || parts[0].equals("::") || parts[0].equals("::1"))) line = parts[1];
            else if (parts.length != 1) return null;
            while (line.startsWith("*") || line.startsWith(".")) line = line.substring(1);
        }
        line = line.toLowerCase(Locale.ROOT);
        while (line.endsWith(".")) line = line.substring(0, line.length() - 1);
        if (line.indexOf('.') < 0 || line.length() > 253 || line.equals("localhost.localdomain")) return null;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (!(ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '.' || ch == '_')) return null;
        }
        return line;
    }

    /** 64-bit FNV-1a over the ASCII name; identical to the Python builder. */
    static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 127) continue;
            h ^= ch;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
