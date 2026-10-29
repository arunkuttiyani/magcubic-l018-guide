package dev.lite.shield;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An on-device DNS filter for the whole box. The VPN routes only one address, a pretend DNS server,
 * so every app's name lookups arrive here and nothing else does: video and downloads never touch
 * this code. A blocked name (ads, pop-ups, trackers, Xiaomi telemetry) gets 0.0.0.0; any other
 * lookup is passed to your router's own DNS server and its answer handed back. No outside service,
 * no lookup logging beyond the last few blocked names shown on the Simple Ad Blocker screen.
 */
public class ShieldService extends VpnService {

    static final String ACTION_STOP = "dev.lite.shield.STOP";
    static final String PREFS = "shield", ENABLED = "enabled";
    static final String DNS_IP = "10.111.222.2";   // the pretend DNS server apps are told to use

    private ParcelFileDescriptor tun;
    private Thread reader;
    private ExecutorService pool;
    private volatile List<InetAddress> upstream = new ArrayList<>();
    private volatile boolean establishFailed = false; // true only when establish() itself failed: let the
    // once-a-minute Watchdog retry instead of onDestroy() restarting instantly in a tight loop

    /** As a foreground service: allowed from the background (boot, watchdog) on Android 8 and later. */
    static void start(Context c) {
        Intent i = new Intent(c, ShieldService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) { c.startService(new Intent(c, ShieldService.class).setAction(ACTION_STOP)); }

    static boolean running() { return Stats.running; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            close();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply();
            Watchdog.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground(); // at once: Android requires it within seconds of a foreground start
        new Thread(this::open, "shield-open").start(); // loading 4.5 MB of list: off the main thread
        return START_STICKY;
    }

    private synchronized void open() {
        if (tun != null) return;
        Lists.load(this);
        upstream = findUpstream();
        Builder b = new Builder()
                .setSession("Simple Ad Blocker")
                .addAddress("10.111.222.1", 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)   // only the DNS address goes through the tunnel
                .setBlocking(true)
                .setMtu(1500);
        try {
            b.addDisallowedApplication(getPackageName()); // our own forwarding and list updates go direct
        } catch (Exception ignored) { }
        try {
            tun = b.establish();
        } catch (Exception e) {
            tun = null;
        }
        if (tun == null) { establishFailed = true; stopForeground(true); stopSelf(); return; } // no VPN permission yet: the screen asks for it
        Stats.running = true;
        Watchdog.schedule(this);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply();
        pool = Executors.newFixedThreadPool(6);
        final ParcelFileDescriptor fd = tun;
        reader = new Thread(() -> loop(fd), "shield-dns");
        reader.start();
        // weekly refresh of the lists, quietly in the background
        if (System.currentTimeMillis() - Lists.builtAt() > 7L * 24 * 3600 * 1000) {
            new Thread(() -> { try { Lists.update(this); } catch (Exception ignored) { } }, "shield-update").start();
        }
    }

    /**
     * A foreground service is one Android treats as in use: it is not reclaimed when memory runs low.
     * The notification is required for that; on a TV it only shows in the notification panel.
     */
    private void goForeground() {
        android.app.Notification.Builder nb;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = new android.app.NotificationChannel("blocker", "Ad blocker",
                    android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            nb = new android.app.Notification.Builder(this, "blocker");
        } else {
            nb = new android.app.Notification.Builder(this);
        }
        nb.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Simple Ad Blocker is on")
                .setContentText("Ads, pop-ups and trackers are blocked")
                .setOngoing(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, ShieldActivity.class), 0));
        startForeground(1, nb.build());
    }

    private void loop(ParcelFileDescriptor fd) {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(fd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(fd.getFileDescriptor())) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                Dns.Query q = Dns.parse(buf, n);
                if (q == null) continue; // not a DNS question (e.g. DNS-over-TLS on 853): dropped, apps fall back
                byte[] packet = java.util.Arrays.copyOf(buf, n);
                Stats.lookups.incrementAndGet();
                if (Lists.blocked(q.name)) {
                    Stats.blocked(q.name);
                    write(out, Dns.blockedReply(packet, q));
                } else {
                    pool.execute(() -> forward(out, packet, q));
                }
            }
        } catch (IOException ignored) {
            // tunnel closed
        }
    }

    /** Asks the router's DNS server and passes its answer back to the app. */
    private void forward(FileOutputStream out, byte[] packet, Dns.Query q) {
        List<InetAddress> servers = upstream;
        if (servers.isEmpty()) { servers = findUpstream(); upstream = servers; }
        for (int attempt = 0; attempt < 2; attempt++) {
            for (InetAddress server : servers) {
                try (DatagramSocket s = new DatagramSocket()) {
                    protect(s);
                    s.setSoTimeout(attempt == 0 ? 2500 : 5000);
                    s.send(new DatagramPacket(packet, q.dnsOff, q.dnsLen, server, 53));
                    byte[] reply = new byte[4096];
                    DatagramPacket r = new DatagramPacket(reply, reply.length);
                    s.receive(r);
                    write(out, Dns.wrap(packet, reply, r.getLength()));
                    return;
                } catch (IOException ignored) {
                    // try the next server
                } catch (IllegalStateException ignored) {
                    // protect(s): the VPN was torn down mid-query; stop trying, the tunnel is gone
                    return;
                }
            }
            servers = findUpstream(); // the network may have changed
            upstream = servers;
        }
    }

    private static void write(FileOutputStream out, byte[] p) {
        synchronized (out) {
            try { out.write(p); } catch (IOException ignored) { }
        }
    }

    /** The DNS servers of the real network (Wi-Fi or Ethernet): your router, normally. */
    private List<InetAddress> findUpstream() {
        List<InetAddress> list = new ArrayList<>();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (InetAddress a : lp.getDnsServers()) if (a instanceof Inet4Address) list.add(a);
        }
        return list;
    }

    private synchronized void close() {
        Stats.running = false;
        stopForeground(true);
        if (pool != null) { pool.shutdownNow(); pool = null; }
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
    }

    @Override public void onRevoke() { close(); super.onRevoke(); } // another VPN took over, or permission revoked

    @Override public void onDestroy() {
        close();
        super.onDestroy();
        boolean skip = establishFailed;
        establishFailed = false;
        // killed while it was meant to be on (not turned off from its screen): come straight back.
        // Not when establish() itself just failed, though — that would retry instantly forever with
        // no delay; the once-a-minute Watchdog already covers that case.
        if (!skip && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED, false) && prepare(this) == null) start(this);
    }
}
