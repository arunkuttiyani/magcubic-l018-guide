package dev.lite.shield;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;

/** One screen: on/off, what it has blocked, the lists it uses, and a button to refresh them. */
public class ShieldActivity extends Activity {

    private static final int REQUEST_VPN = 7;
    private static final int TILE = 0xFF161618;
    private float dp;
    private TextView status, toggle, update, numbers, listInfo;
    private LinearLayout recent;
    private final Handler handler = new Handler();
    private final Runnable tick = this::tick;
    private boolean updating;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        dp = getResources().getDisplayMetrics().density;
        Lists.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF050506);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(40), px(64), px(40));
        scroll.addView(col);

        TextView title = text("Simple Ad Blocker", 28, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        col.addView(title);
        status = text("", 14, 0xA6FFFFFF);
        status.setPadding(0, px(4), 0, px(18));
        col.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        toggle = button(v -> flip());
        update = button(v -> updateLists());
        buttons.addView(toggle, new LinearLayout.LayoutParams(px(220), px(48)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(px(220), px(48));
        gap.leftMargin = px(14);
        buttons.addView(update, gap);
        col.addView(buttons);

        numbers = text("", 15, Color.WHITE);
        numbers.setPadding(0, px(22), 0, 0);
        col.addView(numbers);
        listInfo = text("", 13, 0x99FFFFFF);
        listInfo.setPadding(0, px(4), 0, 0);
        col.addView(listInfo);

        col.addView(heading("RECENTLY BLOCKED"));
        recent = new LinearLayout(this);
        recent.setOrientation(LinearLayout.VERTICAL);
        col.addView(recent);

        col.addView(heading("LISTS"));
        StringBuilder sb = new StringBuilder();
        for (String[] s : Lists.SOURCES) sb.append(sb.length() > 0 ? "  ·  " : "").append(s[0]);
        sb.append("  ·  Xiaomi TV services");
        TextView sources = text(sb.toString(), 13, 0x99FFFFFF);
        col.addView(sources);
        TextView note = text("Blocks ads, pop-ups, trackers and Xiaomi telemetry for every app on the box, browsers included. "
                + "Only name lookups pass through Simple Ad Blocker; video never does. YouTube's own ads can't be blocked this way "
                + "(use SmartTube). Lists refresh weekly on their own.", 12, 0x80FFFFFF);
        note.setPadding(0, px(14), 0, 0);
        col.addView(note);

        setContentView(scroll);
        toggle.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }

    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }

    private void tick() {
        refresh();
        handler.postDelayed(tick, 1000);
    }

    private void flip() {
        if (ShieldService.running()) {
            ShieldService.stop(this);
        } else {
            Intent ask = VpnService.prepare(this); // Android's own permission prompt, first time only
            if (ask != null) { startActivityForResult(ask, REQUEST_VPN); return; }
            ShieldService.start(this);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_VPN && result == RESULT_OK) ShieldService.start(this);
    }

    private void updateLists() {
        if (updating) return;
        updating = true;
        refresh();
        new Thread(() -> {
            String msg;
            try {
                msg = "Updated: " + String.format("%,d", Lists.update(this)) + " blocked domains.";
            } catch (Exception e) {
                msg = "Couldn't update (" + e.getMessage() + "). The current lists stay in use.";
            }
            String m = msg;
            runOnUiThread(() -> { updating = false; listInfo.setTag(m); refresh(); });
        }, "shield-update").start();
    }

    private void refresh() {
        boolean on = ShieldService.running();
        status.setText(on ? "On. Ads, pop-ups, trackers and Xiaomi telemetry are blocked across the whole box."
                : "Off. Nothing is blocked.");
        toggle.setText(on ? "Turn off" : "Turn on");
        update.setText(updating ? "Updating…" : "Update lists");
        long all = Stats.lookups.get(), blocked = Stats.blocked.get();
        numbers.setText(on ? String.format("%,d lookups · %,d blocked (%d%%) since it started", all, blocked,
                all == 0 ? 0 : Math.round(100.0 * blocked / all)) : "");
        String when = Lists.builtAt() == 0 ? "" : " · lists from " + DateFormat.getMediumDateFormat(this).format(new Date(Lists.builtAt()));
        Object extra = listInfo.getTag();
        listInfo.setText(String.format("%,d blocked domains", Lists.size()) + when + (extra != null ? "\n" + extra : ""));
        recent.removeAllViews();
        for (String n : Stats.recent()) recent.addView(text(n, 13, 0xCCFFFFFF));
        if (recent.getChildCount() == 0) recent.addView(text(on ? "Nothing yet." : "—", 13, 0x66FFFFFF));
    }

    private TextView button(View.OnClickListener click) {
        TextView b = text("", 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(TILE);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : TILE);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        b.setOnClickListener(click);
        return b;
    }

    private TextView heading(String s) {
        TextView h = text(s, 11, 0x80FFFFFF);
        h.setLetterSpacing(0.08f);
        h.setPadding(0, px(26), 0, px(8));
        return h;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }
}
