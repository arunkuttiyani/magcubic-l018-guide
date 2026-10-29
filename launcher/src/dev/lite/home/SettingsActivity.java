package dev.lite.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Launcher settings, opened by holding OK on the network pill. One list in the Control Center's
 * style: OK, LEFT or RIGHT change a value; actions run on OK. Changes apply when you go back.
 */
public class SettingsActivity extends Activity {

    private static final int TILE = 0xFF161618, TILE_FOCUS = Color.WHITE;

    /** One setting row. Static, package-private (see Callbacks for why). */
    static class Item {
        String title;
        String[] values;      // null for actions
        String key;           // preference key for choice rows
        boolean bool;         // stored as a boolean (values: Off, On)
        Runnable action;      // for action rows
        TextView value, sub;
    }

    private SharedPreferences prefs;
    private float dp;
    private LinearLayout list;
    private final List<Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050506);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(24), px(40), px(24), px(60));
        list.setClipToPadding(false);
        list.setClipChildren(false);
        scroll.addView(list);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(520), -1, Gravity.CENTER_HORIZONTAL);
        root.addView(scroll, lp);
        setContentView(root);

        header("Lite Home settings", "Changes apply when you go back to the home screen.");

        section("Layout");
        choice("Apps per row", Prefs.COLS, new String[]{"4", "5", "6"}, new int[]{4, 5, 6}, 4);
        choice("Tile size", Prefs.TILE, new String[]{"Small", "Medium", "Large"}, new int[]{0, 1, 2}, 1);
        choice("App names", Prefs.LABELS, new String[]{"Always", "Selected app only", "Never"}, new int[]{0, 1, 2}, Prefs.LABELS_FOCUSED);

        section("Look and feel");
        setSub(choice("Animations", Prefs.MOTION, new String[]{"Full", "Reduced", "Off"}, new int[]{0, 1, 2}, 0).sub,
                "Reduced or Off for slower TVs");
        toggle("Ambient glow behind the selected app", Prefs.TINT, true);
        choice("Clock", Prefs.CLOCK, new String[]{"System", "12-hour", "24-hour"}, new int[]{0, 1, 2}, 0);
        toggle("Show the date", Prefs.DATE, true);

        section("Weather");
        Item wx = toggle("Weather", Prefs.WEATHER, true);
        wx.key = "weather";

        section("Apps");
        Item hidden = action("Show hidden apps", () -> {
            prefs.edit().remove("hidden").apply();
            Toast.makeText(this, "All hidden apps are back.", Toast.LENGTH_SHORT).show();
            refreshCounts();
        });
        hidden.key = "hidden";
        Item reset = action("Reset app order", () -> {
            prefs.edit().remove("order").apply();
            Toast.makeText(this, "Apps are back in alphabetical order.", Toast.LENGTH_SHORT).show();
        });
        setSub(reset.sub, "Alphabetical");

        section("System");
        Item home = action("Set as default home screen", () -> DefaultHome.request(this));
        home.key = "default_home";
        setSub(action("About", null).sub, "Lite Home " + version() + " · no tracking · only network use: weather (Open-Meteo)");
        action("Done", this::finish); // a reachable D-pad exit alongside BACK, in case the remote's BACK key misbehaves

        refreshCounts();
        list.post(() -> {
            // first setting row (headings and section titles aren't focusable)
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) { list.getChildAt(i).requestFocus(); break; }
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCounts();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == DefaultHome.REQUEST) {
            refreshCounts();
            if (!DefaultHome.isDefault(this)) DefaultHome.explain(this);
        }
    }

    private void refreshCounts() {
        for (Item it : items) {
            if ("hidden".equals(it.key)) {
                Set<String> h = prefs.getStringSet("hidden", Collections.emptySet());
                setSub(it.sub, h.isEmpty() ? "No apps are hidden" : h.size() + " hidden");
            } else if ("weather".equals(it.key)) {
                String c = prefs.getString(Prefs.CITY, null);
                setSub(it.sub, (c != null ? c.split(",")[0] + " (from the TV's time zone)" : "Location from the TV's time zone")
                        + " · HTTPS · Off = no network use");
            } else if ("default_home".equals(it.key)) {
                setSub(it.sub, DefaultHome.isDefault(this) ? "Lite Home is your home screen" : "Not the home screen yet");
            }
        }
    }

    // ---- rows ----------------------------------------------------------------------------

    private void header(String title, String sub) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        list.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0x99FFFFFF);
        s.setTextSize(12.5f);
        s.setPadding(0, px(2), 0, px(4));
        list.addView(s);
    }

    private void section(String name) {
        TextView t = new TextView(this);
        t.setText(name.toUpperCase());
        t.setTextColor(0x80FFFFFF);
        t.setTextSize(11);
        t.setLetterSpacing(0.08f);
        t.setPadding(px(4), px(18), 0, px(6));
        list.addView(t);
    }

    private Item choice(String title, String key, String[] labels, int[] values, int def) {
        Item it = new Item();
        it.title = title;
        it.key = key;
        it.values = labels;
        int cur = prefs.getInt(key, def), idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) idx = i;
        final int[] at = {idx};
        row(it, labels[idx], dir -> {
            at[0] = (at[0] + dir + labels.length) % labels.length;
            prefs.edit().putInt(key, values[at[0]]).apply();
            it.value.setText(labels[at[0]]);
        });
        return it;
    }

    private Item toggle(String title, String key, boolean def) {
        Item it = new Item();
        it.title = title;
        it.key = key;
        it.bool = true;
        it.values = new String[]{"Off", "On"};
        row(it, prefs.getBoolean(key, def) ? "On" : "Off", dir -> {
            boolean now = !prefs.getBoolean(key, def);
            prefs.edit().putBoolean(key, now).apply();
            it.value.setText(now ? "On" : "Off");
        });
        return it;
    }

    private Item action(String title, Runnable run) {
        Item it = new Item();
        it.title = title;
        it.action = run;
        row(it, run == null ? "" : "›", dir -> { if (run != null && dir == 0) run.run(); });
        return it;
    }

    /** Builds one focusable row. change.step(dir): dir is -1/+1 for LEFT/RIGHT, 0 for OK. */
    private void row(Item it, String value, Callbacks.Step change) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(16), 0, px(16), 0);
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TILE);
        bg.setCornerRadius(px(12));
        r.setBackground(bg);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(it.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setSingleLine(true);
        it.sub = new TextView(this);
        it.sub.setTextColor(0x99FFFFFF);
        it.sub.setTextSize(11.5f);
        it.sub.setSingleLine(true);
        it.sub.setVisibility(View.GONE); // shown only when a row has a subtitle, so titles centre
        texts.addView(title);
        texts.addView(it.sub);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        it.value = new TextView(this);
        it.value.setText(value);
        it.value.setTextColor(0xCCFFFFFF);
        it.value.setTextSize(13);
        r.addView(it.value);

        r.setOnFocusChangeListener((v, has) -> {
            ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), has ? TILE : TILE_FOCUS, has ? TILE_FOCUS : TILE);
            a.setDuration(140);
            a.addUpdateListener(va -> bg.setColor((int) va.getAnimatedValue()));
            a.start();
            int fg = has ? 0xFF111111 : Color.WHITE;
            title.setTextColor(fg);
            it.sub.setTextColor(has ? 0x99111111 : 0x99FFFFFF);
            it.value.setTextColor(has ? 0xFF111111 : 0xCCFFFFFF);
            // no growing: like tvOS, the selected row simply turns white
        });
        r.setOnClickListener(v -> change.step(it.values != null ? 1 : 0));
        r.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || it.values == null) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { change.step(-1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { change.step(1); return true; }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(50));
        lp.setMargins(0, 0, 0, px(6));
        list.addView(r, lp);
        items.add(it);
    }

    private static void setSub(TextView sub, CharSequence text) {
        sub.setText(text);
        sub.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private int px(float v) { return Math.round(v * dp); }
}
