package dev.lite.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Control Center: a solid panel that slides in from the right with the time, network,
 * Bluetooth, display and storage. The focused row turns white, Apple TV style.
 */
final class ControlPanel {

    // Compact, tvOS-like proportions: a narrow panel, slim rows, small type.
    static final int WIDTH = 300, ROW_H = 54;
    private static final int TILE = 0xFF161618, TILE_FOCUS = Color.WHITE;
    private static final int PANEL = 0xFF0C0C0D;

    /** One row: icon, title, subtitle. Static, package-private fields (see Callbacks for why). */
    static class Row {
        LinearLayout root;
        View icon;
        TextView title, sub;
        GradientDrawable bg, chip;
    }

    final HomeActivity home;
    final View scrim;
    final LinearLayout view;
    private final List<Row> rows = new ArrayList<>();
    private Row network, bluetooth, storage;
    private NetworkIcon netIcon;
    private View returnFocus;
    private boolean open;

    ControlPanel(HomeActivity home) {
        this.home = home;

        scrim = new View(home);
        scrim.setBackgroundColor(0x80000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);

        view = new LinearLayout(home);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(px(12), px(16), px(12), px(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(px(22));
        bg.setStroke(Math.max(1, px(0.75f)), 0x14FFFFFF);
        view.setBackground(bg);
        view.setElevation(px(12));
        view.setVisibility(View.GONE);

        TextClock time = new TextClock(home);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(28);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextClock date = new TextClock(home);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0x99FFFFFF);
        date.setTextSize(12);
        time.setPadding(px(8), 0, 0, 0);
        date.setPadding(px(9), 0, 0, px(12));
        view.addView(time);
        view.addView(date);

        netIcon = new NetworkIcon(home);
        network = row(netIcon, () -> home.startSettings(Settings.ACTION_WIFI_SETTINGS));
        bluetooth = row(icon(R.drawable.ic_bluetooth), () -> home.startSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        Row display = row(icon(R.drawable.ic_display), this::openDisplay);
        display.title.setText("Display & sound");
        display.sub.setText("Resolution, HDR, audio");
        storage = row(icon(R.drawable.ic_storage), () -> home.startSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
    }

    private View icon(int res) {
        ImageView v = new ImageView(home);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        return v;
    }

    private Row row(View icon, Runnable action) {
        Row r = new Row();
        r.root = new LinearLayout(home);
        r.root.setGravity(Gravity.CENTER_VERTICAL);
        r.root.setPadding(px(10), 0, px(12), 0);
        r.root.setFocusable(true);
        r.root.setClickable(true);
        r.bg = new GradientDrawable();
        r.bg.setColor(TILE);
        r.bg.setCornerRadius(px(12));
        r.root.setBackground(r.bg);
        // icon in a small round chip
        android.widget.FrameLayout chip = new android.widget.FrameLayout(home);
        r.chip = new GradientDrawable();
        r.chip.setShape(GradientDrawable.OVAL);
        r.chip.setColor(0x1FFFFFFF);
        chip.setBackground(r.chip);
        r.icon = icon;
        chip.addView(icon, new android.widget.FrameLayout.LayoutParams(px(17), px(17), Gravity.CENTER));
        r.root.addView(chip, new LinearLayout.LayoutParams(px(32), px(32)));

        LinearLayout texts = new LinearLayout(home);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(12), 0, 0, 0);
        r.title = new TextView(home);
        r.title.setTextColor(Color.WHITE);
        r.title.setTextSize(14);
        r.title.setSingleLine(true);
        r.sub = new TextView(home);
        r.sub.setTextColor(0xA6FFFFFF);
        r.sub.setTextSize(11.5f);
        r.sub.setSingleLine(true);
        r.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(r.title);
        texts.addView(r.sub);
        r.root.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        r.root.setOnFocusChangeListener((v, has) -> focus(r, has));
        r.root.setOnClickListener(v -> { close(); action.run(); });
        r.root.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN) { close(); return true; }
            return false;
        });

        // every card the same height, whether its subtitle has one line or two
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(ROW_H));
        lp.setMargins(0, rows.isEmpty() ? 0 : px(6), 0, 0);
        view.addView(r.root, lp);
        rows.add(r);
        return r;
    }

    private void focus(Row r, boolean has) {
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), has ? TILE : TILE_FOCUS, has ? TILE_FOCUS : TILE);
        a.setDuration(home.cfg.ms(150));
        a.addUpdateListener(va -> r.bg.setColor((int) va.getAnimatedValue()));
        a.start();
        int fg = has ? 0xFF111111 : Color.WHITE;
        r.title.setTextColor(fg);
        r.sub.setTextColor(has ? 0xA6111111 : 0xA6FFFFFF);
        if (r.icon instanceof NetworkIcon) ((NetworkIcon) r.icon).setColor(fg);
        else ((ImageView) r.icon).setImageTintList(ColorStateList.valueOf(fg));
        r.chip.setColor(has ? 0x14000000 : 0x1FFFFFFF);
        // no growing: like tvOS, the selected row simply turns white
    }

    boolean isOpen() { return open; }

    void open(View from) {
        if (open) return;
        open = true;
        returnFocus = from;
        refresh();
        home.setBackgroundFocusable(false);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(home.cfg.ms(260)).start();
        view.setVisibility(View.VISIBLE);
        view.setTranslationX(px(WIDTH + 24));
        view.animate().translationX(0).setDuration(home.cfg.ms(320)).setInterpolator(HomeActivity.EASE).start();
        rows.get(0).root.requestFocus();
    }

    void close() {
        if (!open) return;
        open = false;
        scrim.animate().alpha(0f).setDuration(home.cfg.ms(200)).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        view.animate().translationX(px(WIDTH + 24)).setDuration(home.cfg.ms(220)).setInterpolator(new AccelerateInterpolator(1.6f))
                .withEndAction(() -> view.setVisibility(View.GONE)).start();
        home.setBackgroundFocusable(true);
        if (returnFocus != null) returnFocus.requestFocus();
    }

    /** Re-reads network, Bluetooth, storage and memory. Cheap; called on open and on changes. */
    void refresh() {
        NetState n = home.net;
        netIcon.set(n.type, n.level, n.validated);
        network.title.setText(n.title());
        network.sub.setText(n.subtitle());

        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        bluetooth.title.setText("Bluetooth");
        if (ad == null) {
            bluetooth.sub.setText("Not available");
        } else if (!ad.isEnabled()) {
            bluetooth.sub.setText("Off");
        } else {
            StringBuilder names = new StringBuilder();
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            int shown = 0;
            if (bonded != null) for (BluetoothDevice d : bonded) {
                if (shown == 2) { names.append(" +").append(bonded.size() - 2); break; }
                if (shown > 0) names.append(", ");
                names.append(d.getName() != null ? d.getName() : d.getAddress());
                shown++;
            }
            bluetooth.sub.setText(shown == 0 ? "On · no paired devices" : "On · " + names);
        }

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) home.getSystemService(android.content.Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        storage.title.setText("Storage & memory");
        storage.sub.setText(gb(fs.getAvailableBytes()) + " free · " + gb(mi.availMem) + " memory free");
    }

    private void openDisplay() {
        // Android TV has no standard display-settings action; try the common ones in turn.
        Intent[] tries = {
                new Intent(Settings.ACTION_DISPLAY_SETTINGS),
                new Intent().setClassName("com.android.tv.settings", "com.droidlogic.tv.settings.display.DisplayActivity"),
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.display.DisplayActivity"),
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(home.getPackageManager()) != null) {
                try { home.startActivity(i); return; } catch (Exception ignored) { }
            }
        }
        home.startSettings(Settings.ACTION_SETTINGS);
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9);
    }

    private int px(float v) { return home.px(v); }
}
