package dev.lite.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ScrollView;

/**
 * Small adapters. Top-level or static package-private classes only: inner, private and anonymous
 * classes make javac 21 emit metadata that d8 8.2 crashes on.
 */
final class Callbacks {
    private Callbacks() {}

    static class Receiver extends BroadcastReceiver {
        final Runnable r;
        Receiver(Runnable r) { this.r = r; }
        @Override public void onReceive(Context c, Intent i) { r.run(); }
    }

    static class NetCallback extends ConnectivityManager.NetworkCallback {
        final Runnable r;
        NetCallback(Runnable r) { this.r = r; }
        @Override public void onAvailable(Network n) { r.run(); }
        @Override public void onLost(Network n) { r.run(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { r.run(); }
    }

    static class RoundedOutline extends ViewOutlineProvider {
        final float radius;
        RoundedOutline(float radius) { this.radius = radius; }
        @Override public void getOutline(View v, Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
        }
    }

    /** A settings row's change handler: dir is -1/+1 for LEFT/RIGHT, 0 for OK. */
    interface Step { void step(int dir); }

    /** ScrollView that never jumps on its own when focus moves; HomeActivity animates the scroll instead. */
    static class SteadyScroll extends ScrollView {
        SteadyScroll(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
        // Arrow keys move focus only. Without this, DOWN past the last row makes ScrollView page-scroll
        // on its own and leaves the top row cut off under the clock.
        @Override public boolean executeKeyEvent(android.view.KeyEvent e) { return false; }
    }
}
