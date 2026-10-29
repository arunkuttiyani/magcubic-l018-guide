package dev.lite.shield;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.SystemClock;

/**
 * Keeps the blocker running. Android does not restart a VPN service whose process died (the
 * system holds it bound), so a once-a-minute alarm, kept by the system's alarm service and not by
 * our process, wakes this receiver: if the blocker should be on and isn't, it starts it again.
 */
public class Watchdog extends BroadcastReceiver {

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000, 60_000, pending(c));
    }

    static void cancel(Context c) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(pending(c));
    }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, Watchdog.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override public void onReceive(Context c, Intent i) {
        boolean wanted = c.getSharedPreferences(ShieldService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ShieldService.ENABLED, false);
        if (!wanted) { cancel(c); return; }
        if (!ShieldService.running() && VpnService.prepare(c) == null) ShieldService.start(c);
    }
}
