package dev.lite.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/**
 * Becoming the home screen. Android 10+ has an official request (RoleManager.ROLE_HOME) that shows
 * the system's own confirmation; older versions use the "Home app" settings screen where it exists.
 * Many Android TV builds still give their stock launcher priority over any choice, so when neither
 * works we explain the one-line ADB command instead.
 */
final class DefaultHome {
    private DefaultHome() {}

    static final int REQUEST = 41;

    static boolean isDefault(Context c) {
        ResolveInfo r = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    static void request(Activity a) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = a.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try { a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST); return; }
                catch (Exception ignored) { }
            }
        }
        Intent home = new Intent(Settings.ACTION_HOME_SETTINGS);
        if (home.resolveActivity(a.getPackageManager()) != null) {
            try { a.startActivity(home); return; } catch (Exception ignored) { }
        }
        explain(a);
    }

    /** Shown after a request when the system still prefers its own launcher. */
    static void explain(Activity a) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Make Lite Home the home screen")
                .setMessage("This TV keeps its built-in launcher in charge. From a computer with ADB, run:\n\n"
                        + "adb shell pm disable-user --user 0 <built-in launcher>\n\n"
                        + "Android TV: com.google.android.tvlauncher\n"
                        + "Google TV: com.google.android.apps.tv.launcherx\n\n"
                        + "To undo: adb shell pm enable <same package>")
                .setPositiveButton("OK", null)
                .show();
    }
}
