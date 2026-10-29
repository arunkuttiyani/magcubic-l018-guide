package dev.lite.home;

/** Snapshot of the current connection, shown in the status pill and the Control Center. */
final class NetState {
    int type = NetworkIcon.OFFLINE;
    int level;            // Wi-Fi bars 0..3
    boolean validated;    // false = connected but no internet
    String name;          // Wi-Fi name when Android reveals it, else null
    String ip;
    int mbps;

    String title() {
        if (type == NetworkIcon.OFFLINE) return "Not connected";
        if (type == NetworkIcon.ETHERNET) return "Ethernet";
        return name != null ? name : "Wi-Fi";
    }

    /** One compact line, e.g. "Excellent · 72 Mbps · 192.168.0.227". */
    String subtitle() {
        if (type == NetworkIcon.OFFLINE) return "Select to connect";
        StringBuilder s = new StringBuilder();
        if (!validated) s.append("No internet");
        else if (type == NetworkIcon.WIFI) s.append(new String[]{"Weak", "Fair", "Good", "Excellent"}[Math.max(0, Math.min(3, level))]);
        else s.append("Connected");
        if (mbps > 0) s.append(" · ").append(mbps).append(" Mbps");
        if (ip != null) s.append(" · ").append(ip);
        return s.toString();
    }
}
