package dev.lite.shield;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.SystemClock;

/**
 * Turns the shield back on after a reboot or an update, if it was on before. The start itself is
 * deferred ~20s: right at BOOT_COMPLETED every app's own boot receiver is starting a process at
 * once on this box's 1GB of RAM, and loading the 4.5MB blocklist is one more thing competing for
 * that window. A short delay lets the worst of that settle first.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_DEFERRED_START = "dev.lite.shield.DEFERRED_START";

    @Override public void onReceive(Context c, Intent i) {
        if (ACTION_DEFERRED_START.equals(i.getAction())) {
            if (VpnService.prepare(c) == null) { ShieldService.start(c); Watchdog.schedule(c); }
            return;
        }
        boolean wasOn = c.getSharedPreferences(ShieldService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ShieldService.ENABLED, false);
        if (!wasOn) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1,
                new Intent(c, BootReceiver.class).setAction(ACTION_DEFERRED_START), PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 20_000, pi);
    }
}
