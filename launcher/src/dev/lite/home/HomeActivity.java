package dev.lite.home;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lite Home 4: an Apple TV style home on a black screen.
 *
 *   - a greeting, a thin Outfit clock with the weather beside it, and the date;
 *   - Wi-Fi and Settings pills at the top right;
 *   - the first row is a dock in a rounded band, with the focused app's name above it in large type
 *     and an ambient glow made from its artwork; moving down slides the grid up and the glow away;
 *   - the selected tile lifts, gains a bright rim and a soft shadow, and shows its name; the selection
 *     fades out on one tile and in on the next, it never slides across the screen.
 *
 *   Hold OK on a tile                 jiggle mode: move it with the arrows, OK when done
 *   MENU on a tile                    hide, app info, uninstall
 *   OK on the Wi-Fi pill              Control Center (also: RIGHT at the end of a row)
 *   Hold OK on either pill            launcher settings
 *
 * Design after CleanMyTV (github.com/Supopo/CleanMyTV, MIT): see GlassView and Art.vividColor.
 * Performance: tiles never redraw on focus; rim and shadow are separate views that only fade, the focused tile
 * scales on the RenderThread, and the glow cross-fades two tiny stretched bitmaps.
 */
public class HomeActivity extends Activity {

    static final String SETTINGS_KEY = "__settings__";
    // Launchers and the TV settings app: the Settings pill opens settings instead.
    static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "dev.lite.home", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.android.tv.settings"));

    static final Interpolator EASE = new DecelerateInterpolator(2f);
    static final Interpolator SETTLE = new android.view.animation.OvershootInterpolator(1.1f);

    // Layout, in dp
    // CleanMyTV's design: 180 x 101 dp tiles, four across a 1080p screen, 16 dp corners, a 10% lift.
    static final int SIDE = 58, BAND_PAD = 14, MIN_GAP = 22, MAX_GAP = 40, DOCK_UP = 168, DOCK_BOTTOM = 40;
    static final float LIFT = 1.10f;
    static final long IDLE_MS = 60_000;

    float dp;
    SharedPreferences prefs;
    Prefs cfg;
    String cfgSignature;
    int cols, cardW, cardH, rowH, dockH, dockW, gap, dockRest; // dp
    float radius; // dp
    int liftW, liftH;      // px: the selected tile's exact size, grown by whole pixels on each side
    float liftX, liftY;    // the matching scale factors

    FrameLayout root, content, glowBox;
    View band, topFade;
    // the pills' own shelf: a title and a glow of their own instead of the last app's colour
    final AppInfo wifiShelf = new AppInfo(), settingsShelf = new AppInfo();
    final AppInfo inputsShelf = new AppInfo(), projectorShelf = new AppInfo();
    BitmapDrawable glowMask;
    int glowColor;
    ValueAnimator glowFade;
    TextView shelfTitle, greeting;
    LinearLayout rows;
    android.graphics.Bitmap shadowBmp; // one blurred shadow, shared by every tile (rebuilt when tiles resize)
    GradientDrawable bandTint;
    TextClock time, ampm, date;
    LinearLayout weatherBox, wifiPill, settingsPill, inputsPill, projectorPill;
    WeatherIcon wxIcon;
    TextView wxTemp, wxDetails, wifiLabel;
    NetworkIcon wifiIcon;
    ValueAnimator wxExpand;
    boolean wxExpanded;
    ControlPanel panel;

    final Handler handler = new Handler();
    final Runnable wxIdle = this::expandWeather;
    final Runnable wxTick = this::weatherTick; // method references: d8 8.2 crashes on anonymous classes
    final Runnable greetTick = this::greetingTick;
    final Runnable loadRun = this::loadNow;
    int loadGen;

    List<AppInfo> apps = new ArrayList<>();
    final Map<String, Integer> rowOf = new HashMap<>();
    AppInfo moving, shelfApp;
    Jiggle jiggle;
    String focusKey;
    int focusRow = 0;
    boolean resumed, dirty = true, shownOnce;
    final NetState net = new NetState();

    final Callbacks.Receiver packagesChanged = new Callbacks.Receiver(() -> {
        dirty = true;
        if (resumed) load();
    });
    final Callbacks.Receiver radioChanged = new Callbacks.Receiver(() -> updateStatus());
    Callbacks.NetCallback netCallback;

    // ---- setup ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE); // same file as v1–v3: keeps your order
        applyPrefs();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        buildGlow();
        buildShelfTitle();
        buildContent();
        buildHeader();
        buildPills();
        panel = new ControlPanel(this);
        root.addView(panel.scrim, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px(ControlPanel.WIDTH), -2, Gravity.END | Gravity.TOP);
        plp.setMargins(0, px(24), px(24), 0);
        root.addView(panel.view, plp);
        setContentView(root);
        getWindow().setBackgroundDrawable(null); // root paints every pixel; don't draw the window under it too
        applyClockFormat();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        IntentFilter r = new IntentFilter(WifiManager.RSSI_CHANGED_ACTION);
        r.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        r.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        r.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        r.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        r.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(radioChanged, r);
        if (Build.VERSION.SDK_INT >= 24) {
            netCallback = new Callbacks.NetCallback(() -> runOnUiThread(this::updateStatus));
            connectivity().registerDefaultNetworkCallback(netCallback);
        }
        updateStatus();
    }

    /** Reads settings and sizes the tiles so the dock band fits the screen width. */
    private void applyPrefs() {
        cfg = Prefs.load(this);
        cfgSignature = cfg.signature();
        cols = Math.max(3, Math.min(7, cfg.cols));
        float screenW = getResources().getDisplayMetrics().widthPixels / dp;
        float screenH = getResources().getDisplayMetrics().heightPixels / dp;
        float inner = screenW - 2 * SIDE - 2 * BAND_PAD;
        // the tile size you chose, shrunk only when the row can't fit it; the gaps spread the tiles
        // across the band (32 dp at the default four), up to a limit, then the band hugs them
        cardW = (int) Math.min(cfg.tileWidthDp(), (inner - (cols - 1) * MIN_GAP) / cols);
        gap = cols > 1 ? (int) Math.min(MAX_GAP, (inner - cols * cardW) / (cols - 1)) : 0;
        cardH = Math.round(cardW * 101f / 180f);
        radius = 16f * Math.min(1f, cardW / 180f);
        dockH = cardH + 2 * BAND_PAD;
        dockW = cols * cardW + (cols - 1) * gap + 2 * BAND_PAD;
        dockRest = Math.round(screenH - DOCK_BOTTOM - dockH); // the dock sits low, close to the bottom edge
        // ~10% bigger, but by a whole number of pixels per side, so the lifted tile's edges and its
        // artwork land exactly on screen pixels instead of being blended across two
        int w = px(cardW), h = px(cardH);
        liftW = w + 2 * Math.round(w * (LIFT - 1) / 2);
        liftH = h + 2 * Math.round(h * (LIFT - 1) / 2);
        liftX = liftW / (float) w;
        liftY = liftH / (float) h;
        rowH = cardH + (cfg.labels == Prefs.LABELS_NEVER ? 22 : 46);
    }

    /**
     * CleanMyTV's ambient glow: one soft radial light, tinted with the focused app's most vivid colour,
     * behind the dock's title. A single 480x270 mask stretched over the screen; changing app only changes
     * its tint, so it costs nothing per frame beyond the colour fade.
     */
    private void buildGlow() {
        glowBox = new FrameLayout(this);
        int w = 480, h = 270;
        android.graphics.Bitmap mask = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.RadialGradient(w * 0.32f, h * 0.48f, w * 0.66f,
                new int[]{0x8CFFFFFF, 0x3DFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        new android.graphics.Canvas(mask).drawRect(0, 0, w, h, paint);
        glowMask = new BitmapDrawable(getResources(), mask);
        glowMask.setFilterBitmap(true);
        View light = new View(this);
        light.setBackground(glowMask);
        glowBox.addView(light, new FrameLayout.LayoutParams(-1, -1));
        glowBox.setAlpha(0f);
        root.addView(glowBox, new FrameLayout.LayoutParams(-1, -1));
    }

    private void tintGlow(int color) {
        glowColor = color;
        glowMask.setTint(color); // SRC_IN: the mask's shape, the app's colour
        bandTint.setColor((color & 0x00FFFFFF) | 0x38000000);
    }

    private void buildShelfTitle() {
        shelfTitle = new TextView(this);
        shelfTitle.setTypeface(Fonts.light(this));
        shelfTitle.setTextColor(Color.WHITE);
        shelfTitle.setTextSize(48);
        shelfTitle.setSingleLine(true);
        shelfTitle.setEllipsize(TextUtils.TruncateAt.END);
        shelfTitle.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        lp.setMargins(px(dockLeft() + 4), px(dockRest - 98), px(SIDE), 0); // 48sp name, 40dp above the band
        root.addView(shelfTitle, lp);
    }

    private void buildContent() {
        content = new FrameLayout(this);
        content.setClipChildren(false);
        // The dock's band (CleanMyTV's glass_panel) is its own view so it fades on the RenderThread.
        // Light frosted glass (CleanMyTV's light band) over a faint wash of the selected app's colour,
        // which glides with the ambient glow as you move, so the dock takes on each app's mood.
        band = new View(this);
        bandTint = new GradientDrawable();
        bandTint.setCornerRadius(px(radius + BAND_PAD));
        bandTint.setColor(0x00000000);
        GradientDrawable frost = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x0FFFFFFF});
        frost.setCornerRadius(px(radius + BAND_PAD));
        frost.setStroke(Math.max(1, px(1)), 0x40FFFFFF);
        band.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{bandTint, frost}));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(px(dockW), px(dockH), Gravity.CENTER_HORIZONTAL);
        content.addView(band, blp);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setGravity(Gravity.CENTER_HORIZONTAL);
        rows.setClipChildren(false);
        rows.setPadding(px(SIDE), 0, px(SIDE), px(160));
        content.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        content.setTranslationY(px(dockRest));
        root.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Rows scrolling up fade out under the header instead of being cut off.
        topFade = new View(this);
        topFade.setAlpha(0f); // only in the grid: in the dock the glow runs up behind the clock
        topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF000000, 0xFF000000, 0xFF000000, 0x00000000}));
        root.addView(topFade, new FrameLayout.LayoutParams(-1, px(DOCK_UP + 8))); // solid under the header, clear by the first grid row
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        greeting = new TextView(this);
        greeting.setTypeface(Fonts.regular(this));
        greeting.setTextColor(0x8CFFFFFF);
        greeting.setTextSize(11);
        greeting.setLetterSpacing(0.2f);
        header.addView(greeting);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        time = new TextClock(this);
        time.setTypeface(Fonts.light(this));
        time.setTextColor(Color.WHITE);
        time.setTextSize(48);
        time.setIncludeFontPadding(false);
        ampm = new TextClock(this);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("a"); // shown only in 12-hour mode, which can be forced on a 24-hour system
        ampm.setTypeface(Fonts.regular(this));
        ampm.setTextColor(0xB3FFFFFF);
        ampm.setTextSize(15);
        ampm.setPadding(px(6), px(14), 0, 0);
        timeRow.addView(time);
        timeRow.addView(ampm);

        // Weather beside the clock; the details unfold to the right after a minute without input.
        weatherBox = new LinearLayout(this);
        weatherBox.setGravity(Gravity.CENTER_VERTICAL);
        weatherBox.setPadding(px(22), px(2), 0, 0);
        weatherBox.setVisibility(View.GONE);
        wxIcon = new WeatherIcon(this);
        wxTemp = new TextView(this);
        wxTemp.setTypeface(Fonts.light(this));
        wxTemp.setTextColor(Color.WHITE);
        wxTemp.setTextSize(30);
        wxTemp.setPadding(px(6), 0, 0, 0);
        wxDetails = new TextView(this);
        wxDetails.setTypeface(Fonts.regular(this));
        wxDetails.setTextColor(0xCCFFFFFF);
        wxDetails.setTextSize(16);
        wxDetails.setSingleLine(true);
        wxDetails.setEllipsize(TextUtils.TruncateAt.END);
        wxDetails.setMaxWidth(px(290)); // never reaches the pills
        wxDetails.setPadding(px(16), px(4), 0, 0);
        wxDetails.setAlpha(0f);
        weatherBox.addView(wxIcon, new LinearLayout.LayoutParams(px(46), px(46)));
        weatherBox.addView(wxTemp);
        weatherBox.addView(wxDetails);
        timeRow.addView(weatherBox);
        header.addView(timeRow);

        date = new TextClock(this);
        date.setFormat12Hour("EEEE  d MMMM");
        date.setFormat24Hour("EEEE  d MMMM");
        date.setAllCaps(true);
        date.setTypeface(Fonts.regular(this));
        date.setTextColor(0x99FFFFFF);
        date.setTextSize(13);
        date.setLetterSpacing(0.15f);
        header.addView(date);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP);
        lp.setMargins(px(SIDE), px(30), 0, 0);
        root.addView(header, lp);
    }

    private void buildPills() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // the focused pill grows 6%; without this the bar cuts off its rounded outer end
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(px(6), px(4), px(6), px(4));
        wifiIcon = new NetworkIcon(this);
        wifiLabel = pillText();
        wifiPill = pill(wifiIcon, wifiLabel);
        wifiPill.setOnClickListener(v -> panel.open(v));

        // Projector-specific: an input/source switcher (HDMI, AV, etc.) — this
        // device is a projector, not a plain TV, so source switching lives here.
        ImageView inputsIcon = new ImageView(this);
        inputsIcon.setImageResource(R.drawable.ic_input);
        inputsIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        TextView inputsLabel = pillText();
        inputsLabel.setText("Inputs");
        inputsPill = pill(inputsIcon, inputsLabel);
        inputsPill.setOnClickListener(v -> startInputs());

        // Projector-specific: keystone/trapezoidal correction, screen size, focus.
        ImageView projectorIcon = new ImageView(this);
        projectorIcon.setImageResource(R.drawable.ic_projector);
        projectorIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        TextView projectorLabel = pillText();
        projectorLabel.setText("Projector");
        projectorPill = pill(projectorIcon, projectorLabel);
        projectorPill.setOnClickListener(v -> startProjector());

        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.gear);
        gear.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        TextView gearLabel = pillText();
        gearLabel.setText("Settings");
        settingsPill = pill(gear, gearLabel);
        settingsPill.setOnClickListener(v -> startSettings(Settings.ACTION_SETTINGS));

        pillKeys(wifiPill, null, inputsPill);
        pillKeys(inputsPill, wifiPill, projectorPill);
        pillKeys(projectorPill, inputsPill, settingsPill);
        pillKeys(settingsPill, projectorPill, null);

        bar.addView(wifiPill);
        // Each pill already carries its own round-size LayoutParams (set inside pill()) —
        // reusing one LayoutParams instance across addView calls would override that with
        // WRAP_CONTENT on every view but the first, so each gets its margin added directly.
        for (LinearLayout pl : new LinearLayout[]{inputsPill, projectorPill, settingsPill}) {
            ((LinearLayout.LayoutParams) pl.getLayoutParams()).leftMargin = px(12);
            bar.addView(pl);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        lp.setMargins(0, px(48), px(SIDE - 6), 0); // offsets the bar padding
        root.addView(bar, lp);
    }

    private TextView pillText() {
        TextView t = new TextView(this);
        t.setTypeface(Fonts.regular(this));
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setPadding(px(10), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(px(180)); // long network names never reach the weather
        return t;
    }

    private static final int PILL_ROUND = 52; // idle diameter, dp
    private static final int PILL_ICON = 28;  // icon size, dp
    private static final int PILL_PAD = (PILL_ROUND - PILL_ICON) / 2; // 12dp — centers the icon in the idle circle

    /**
     * A round icon button that expands into a frosted pill with its label when focused (CleanMyTV's
     * pill_bg tone): idle is a plain circle, focused grows into a near-white pill with dark contents
     * and the label fades in. Both backgrounds are prebuilt, so focus never re-renders them.
     */
    private LinearLayout pill(View icon, TextView label) {
        LinearLayout p = new LinearLayout(this);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setFocusable(false); // enabled once the grid holds focus, see buildGrid()
        p.setClickable(true);
        GradientDrawable idle = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x14FFFFFF});
        idle.setCornerRadius(px(PILL_ROUND / 2));
        idle.setStroke(Math.max(1, px(1)), 0x38FFFFFF);
        GradientDrawable lit = new GradientDrawable();
        lit.setCornerRadius(px(PILL_ROUND / 2));
        lit.setColor(0xF5FFFFFF);
        p.setBackground(idle);

        int round = px(PILL_ROUND);
        int pad = px(PILL_PAD);
        // Padding is identical in both states — only the pill's width animates — so the
        // icon never shifts position when expanding or collapsing, and looks right from
        // the very first frame (not just after the first focus-change event fires).
        p.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(round, round);
        p.setLayoutParams(plp);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(PILL_ICON), px(PILL_ICON));
        ilp.gravity = Gravity.CENTER_VERTICAL;
        p.addView(icon, ilp);
        label.setAlpha(0f);
        label.setVisibility(View.INVISIBLE);
        p.addView(label);

        p.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? lit : idle);
            int fg = has ? 0xFF111111 : Color.WHITE;
            label.setTextColor(fg);
            if (icon instanceof NetworkIcon) ((NetworkIcon) icon).setColor(fg);
            else ((ImageView) icon).setImageTintList(ColorStateList.valueOf(fg));

            label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int expanded = pad + px(PILL_ICON) + label.getMeasuredWidth() + pad;
            if (p.getTag() instanceof ValueAnimator) ((ValueAnimator) p.getTag()).cancel();
            ValueAnimator wa = ValueAnimator.ofInt(plp.width, has ? expanded : round);
            wa.setDuration(cfg.ms(220)).setInterpolator(EASE);
            wa.addUpdateListener(a -> {
                plp.width = (int) a.getAnimatedValue();
                p.requestLayout();
            });
            p.setTag(wa);
            wa.start();
            if (has) label.animate().alpha(1f).setStartDelay(cfg.ms(90)).setDuration(cfg.ms(130))
                    .withStartAction(() -> label.setVisibility(View.VISIBLE)).start();
            else label.animate().alpha(0f).setDuration(cfg.ms(120))
                    .withEndAction(() -> label.setVisibility(View.INVISIBLE)).start();

            if (has) {
                slideTo(0, true);
                showShelf(p == wifiPill ? wifiShelf : p == inputsPill ? inputsShelf : p == projectorPill ? projectorShelf : settingsShelf);
            }
        });
        p.setOnLongClickListener(v -> { openSettings(); return true; });
        return p;
    }

    /** Pills move only between themselves; DOWN returns to the tile you came from. */
    private void pillKeys(View p, View left, View right) {
        p.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) openSettings();
                return true;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (left != null) left.requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (right != null) right.requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: focusLastCard(); return true;
            }
            return false;
        });
    }

    private void applyClockFormat() {
        boolean h24 = cfg.clock == Prefs.CLOCK_24 || (cfg.clock == Prefs.CLOCK_SYSTEM && android.text.format.DateFormat.is24HourFormat(this));
        time.setFormat12Hour(h24 ? "HH:mm" : "h:mm");
        time.setFormat24Hour(h24 ? "HH:mm" : "h:mm");
        ampm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        date.setVisibility(cfg.date ? View.VISIBLE : View.GONE);
    }

    void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(radioChanged);
        if (netCallback != null) connectivity().unregisterNetworkCallback(netCallback);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!Prefs.load(this).signature().equals(cfgSignature)) {
            // settings changed: re-size everything and redraw
            applyPrefs();
            applyClockFormat();
            resizeDock();
            shownOnce = false;
            dirty = true;
            refreshWeather(true);
        }
        applyClockFormat(); // follows the TV's 12/24-hour setting when the clock is on "System"
        scheduleIdle();
        greetingTick();
        updateStatus();
        if (dirty) load();
        maybeHintDefaultHome();
        showWeather(cfg.weather ? Weather.cached(prefs) : null);
        handler.removeCallbacks(wxTick);
        handler.post(wxTick); // refresh now if stale, then check every 10 minutes while visible
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(wxTick); // no weather requests while an app is in front
        handler.removeCallbacks(wxIdle);
        handler.removeCallbacks(greetTick);
        collapseWeather(false);
        if (moving != null) endMove();
        super.onPause();
    }

    /** On a cold start the grid can be built before the window has focus; focus a tile once it does. */
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has || panel.isOpen() || getCurrentFocus() != null || apps.isEmpty()) return;
        View card = focusKey == null ? null : rows.findViewWithTag(focusKey);
        if (card == null) card = rows.findViewWithTag(apps.get(0).key);
        if (card != null) card.requestFocus();
    }

    @Override public void onBackPressed() {
        if (panel.isOpen()) panel.close();
        else if (moving != null) endMove();
        // otherwise: already home
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: close everything and glide back to the first app.
        if (panel.isOpen()) panel.close();
        if (moving != null) endMove();
        View first = apps.isEmpty() ? null : rows.findViewWithTag(apps.get(0).key);
        if (first != null) first.requestFocus();
    }

    /** Once: if opened as an app rather than as the home screen, say how to make it the default. */
    private void maybeHintDefaultHome() {
        if (prefs.getBoolean("hinted_home", false) || DefaultHome.isDefault(this)) return;
        prefs.edit().putBoolean("hinted_home", true).apply();
        Toast.makeText(this, "Lite Home isn't your home screen yet. Hold OK on the Settings pill, then "
                + "\"Set as default home screen\".", Toast.LENGTH_LONG).show();
    }

    private void greetingTick() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 12 ? "GOOD MORNING" : h >= 12 && h < 17 ? "GOOD AFTERNOON"
                : h >= 17 && h < 22 ? "GOOD EVENING" : "GOOD NIGHT");
        handler.removeCallbacks(greetTick);
        if (resumed) handler.postDelayed(greetTick, 60_000);
    }

    // ---- weather ------------------------------------------------------------------------------

    private void weatherTick() {
        refreshWeather(false);
        handler.postDelayed(wxTick, 10 * 60 * 1000L);
    }

    /** Fetches new weather in the background when the cached copy is older than 30 minutes. */
    void refreshWeather(boolean force) {
        if (!cfg.weather) { showWeather(null); return; }
        Weather cached = Weather.cached(prefs);
        if (!force && cached != null && !cached.stale()) return;
        new Thread(() -> {
            double[] at = Weather.location(prefs);
            Weather w = at == null ? null : Weather.fetch(prefs, at[0], at[1]);
            if (w != null) runOnUiThread(() -> showWeather(w));
        }).start();
    }

    private void showWeather(Weather w) {
        if (w == null || !cfg.weather) { weatherBox.setVisibility(View.GONE); return; }
        boolean f = Weather.fahrenheit(prefs);
        wxIcon.set(w.kind(), w.day);
        wxTemp.setText(Weather.deg(w.temp, f));
        String outlook = w.outlook();
        wxDetails.setText(w.condition() + (outlook != null ? "  ·  " + outlook : "")
                + "  ·  " + Weather.deg(w.max, f) + " / " + Weather.deg(w.min, f)); // today's high / low
        if (weatherBox.getVisibility() != View.VISIBLE) {
            weatherBox.setAlpha(0f);
            weatherBox.setVisibility(View.VISIBLE);
            weatherBox.animate().alpha(1f).setDuration(cfg.ms(300)).start();
        }
    }

    // ---- idle: unfold the weather details ---------------------------------------------------

    /** Any key press counts as activity: fold the details away and restart the idle timer. */
    @Override public void onUserInteraction() {
        super.onUserInteraction();
        if (wxExpanded) collapseWeather(true);
        scheduleIdle();
    }

    private void scheduleIdle() {
        handler.removeCallbacks(wxIdle);
        if (resumed && cfg.weather) handler.postDelayed(wxIdle, IDLE_MS);
    }

    private void expandWeather() {
        if (wxExpanded || weatherBox.getVisibility() != View.VISIBLE || panel.isOpen()) return;
        wxExpanded = true;
        animateDetails(1f, cfg.ms(700));
    }

    private void collapseWeather(boolean animate) {
        if (!wxExpanded) return;
        wxExpanded = false;
        animateDetails(0f, animate ? cfg.ms(250) : 0);
    }

    /** Reveals the details left to right by growing a clip, with a fade; no re-layout per frame. */
    private void animateDetails(float to, long duration) {
        if (wxExpand != null) wxExpand.cancel();
        if (duration == 0) { revealDetails(to); return; }
        wxExpand = ValueAnimator.ofFloat(wxDetails.getAlpha(), to);
        wxExpand.setDuration(duration);
        wxExpand.setInterpolator(EASE);
        wxExpand.addUpdateListener(va -> revealDetails((float) va.getAnimatedValue()));
        wxExpand.start();
    }

    private void revealDetails(float f) {
        wxDetails.setClipBounds(new Rect(0, 0, Math.round(wxDetails.getWidth() * f), wxDetails.getHeight()));
        wxDetails.setAlpha(f);
        wxDetails.setTranslationX((1f - f) * -px(10));
    }

    // ---- status (Wi-Fi pill + Control Center) -------------------------------------------------

    ConnectivityManager connectivity() { return (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE); }

    void updateStatus() {
        ConnectivityManager cm = connectivity();
        net.ip = null;
        net.mbps = 0;
        net.name = null;
        net.type = NetworkIcon.OFFLINE;
        if (Build.VERSION.SDK_INT >= 23) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // a local firewall VPN (e.g. NetGuard) is not the real link; describe Wi-Fi or Ethernet
                for (Network m : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(m);
                    if (c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { n = m; caps = c; break; }
                }
            }
            if (caps != null) {
                net.validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                net.type = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? NetworkIcon.ETHERNET : NetworkIcon.WIFI;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) net.ip = la.getAddress().getHostAddress();
                }
            }
        } else {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                net.validated = true; // Android 5 can't tell
                net.type = ni.getType() == ConnectivityManager.TYPE_ETHERNET ? NetworkIcon.ETHERNET : NetworkIcon.WIFI;
            }
        }
        if (net.type == NetworkIcon.WIFI) {
            WifiInfo wi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            if (wi != null) {
                net.level = Math.max(1, WifiManager.calculateSignalLevel(wi.getRssi(), 4));
                net.mbps = wi.getLinkSpeed();
                String ssid = wi.getSSID();
                // Android 9+ hides the name from apps without location access
                if (ssid != null && !ssid.contains("unknown ssid")) net.name = ssid.replace("\"", "");
            }
        }
        wifiIcon.set(net.type, net.level, net.validated);
        wifiLabel.setText(net.type == NetworkIcon.OFFLINE ? "Offline"
                : net.type == NetworkIcon.ETHERNET ? "Ethernet" : (net.validated ? (net.name != null ? net.name : "Wi-Fi") : "No internet"));
        // the Wi-Fi pill's glow says how the connection is: teal online, amber no internet, red offline
        wifiShelf.label = net.type == NetworkIcon.OFFLINE ? "Offline"
                : !net.validated ? "No internet"
                : net.type == NetworkIcon.ETHERNET ? "Ethernet" : (net.name != null ? net.name : "Wi-Fi");
        wifiShelf.glowColor = net.type == NetworkIcon.OFFLINE ? 0xFFD9443F : !net.validated ? 0xFFE39B2D : 0xFF1FA6C4;
        settingsShelf.label = "Settings";
        settingsShelf.glowColor = 0xFF7385A8; // calm silver-blue
        inputsShelf.label = "Inputs";
        inputsShelf.glowColor = 0xFF37B24D;
        projectorShelf.label = "Projector";
        projectorShelf.glowColor = 0xFFE8590C;
        if (shelfApp == wifiShelf && wifiPill.hasFocus()) { shelfApp = null; showShelf(wifiShelf); } // state changed
        if (panel != null) panel.refresh();
    }

    // ---- apps ----------------------------------------------------------------------------

    /**
     * Reloads the app list. One install or update sends several broadcasts, so wait for them to
     * settle and only keep the newest result: each load renders artwork for every app.
     */
    void load() {
        dirty = false;
        handler.removeCallbacks(loadRun);
        handler.postDelayed(loadRun, shownOnce ? 400 : 0);
    }

    private void loadNow() {
        int gen = ++loadGen, w = px(cardW), h = px(cardH), lw = liftW, lh = liftH;
        float r = px(radius);
        new Thread(() -> {
            List<AppInfo> list = queryApps(w, h, lw, lh, r);
            runOnUiThread(() -> { if (gen == loadGen) adopt(list); });
        }).start();
    }

    /** Swaps in a fresh list; mid-move, keeps the order being edited and follows the moving app. */
    private void adopt(List<AppInfo> list) {
        if (moving != null) {
            AppInfo m = byKey(list, moving.key);
            if (m == null) {
                if (jiggle != null) jiggle.stop();
                moving = null;
            } else {
                Map<String, Integer> rank = new HashMap<>();
                for (int i = 0; i < apps.size(); i++) rank.put(apps.get(i).key, i);
                Collections.sort(list, (x, y) -> {
                    Integer rx = rank.get(x.key), ry = rank.get(y.key);
                    return (rx == null ? Integer.MAX_VALUE : rx) - (ry == null ? Integer.MAX_VALUE : ry);
                });
                moving = m;
            }
        }
        apps = list;
        buildGrid();
    }

    static AppInfo byKey(List<AppInfo> list, String key) {
        for (AppInfo a : list) if (a.key.equals(key)) return a;
        return null;
    }

    private List<AppInfo> queryApps(int w, int h, int lw, int lh, float rad) {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        // Prefer each app's TV (leanback) entry; fall back to its phone launcher entry.
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = r.activityInfo.packageName;
                if (!SKIP.contains(pkg) && !found.containsKey(pkg)) found.put(pkg, r);
            }
        }
        Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
        List<AppInfo> list = new ArrayList<>();
        for (ResolveInfo r : found.values()) {
            ActivityInfo ai = r.activityInfo;
            if (hidden.contains(ai.packageName)) continue;
            AppInfo a = new AppInfo();
            a.key = ai.packageName;
            a.label = String.valueOf(r.loadLabel(pm));
            // Rendered once, at the lifted size, from the app's sharpest artwork: crisp when focused too.
            // The big source drawables are dropped right after; only the tile-sized bitmap is kept.
            // Two pixel-exact copies: one at the resting size, one at the lifted size the selected tile
            // grows to. Each is shown 1:1, so no tile is ever resampled (resampling is what blurred them).
            android.graphics.Bitmap plain = renderArt(pm, r, w, h);
            a.glowColor = Art.vividColor(plain);
            a.art = Art.rounded(plain, rad);
            a.artLift = Art.rounded(renderArt(pm, r, lw, lh), rad * lw / w);
            a.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.launch = new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            list.add(a);
        }
        // Saved order first, everything else alphabetically after it.
        List<String> order = savedOrder();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        Collections.sort(list, (x, y) -> {
            Integer rx = rank.get(x.key), ry = rank.get(y.key);
            if (rx != null && ry != null) return rx - ry;
            if (rx != null) return -1;
            if (ry != null) return 1;
            return x.label.compareToIgnoreCase(y.label);
        });
        return list;
    }

    /** The banner if the app has one, else its icon on a graphite card; a plain card if both fail. */
    private static android.graphics.Bitmap renderArt(PackageManager pm, ResolveInfo r, int w, int h) {
        ActivityInfo ai = r.activityInfo;
        try {
            Drawable banner = Art.hiRes(pm, ai, true);
            if (banner == null) banner = ai.loadBanner(pm);
            if (banner != null) return Art.sharp(banner, w, h);
            Drawable icon = Art.hiRes(pm, ai, false);
            return Art.iconCard(icon != null ? icon : r.loadIcon(pm), w, h);
        } catch (Exception | OutOfMemoryError e) {
            try {
                return Art.iconCard(r.loadIcon(pm), w, h);
            } catch (Exception | OutOfMemoryError e2) {
                android.graphics.Bitmap plain = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
                plain.eraseColor(0xFF1C1C1E);
                return plain;
            }
        }
    }

    /** Fresh artwork for one view: the banner, or the square icon on its colour wash. */
    Drawable artFor(AppInfo a) { return artFor(a.art); }

    Drawable artFor(android.graphics.Bitmap bmp) {
        BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
        d.setFilterBitmap(true);
        return d;
    }

    private List<String> savedOrder() {
        String o = prefs.getString("order", "");
        return o.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(o.split(",")));
    }

    private void saveOrder() {
        List<String> keys = new ArrayList<>();
        for (AppInfo a : apps) keys.add(a.key);
        prefs.edit().putString("order", TextUtils.join(",", keys)).apply();
    }

    // ---- dock + grid -----------------------------------------------------------------------

    void buildGrid() {
        View cur = getCurrentFocus();
        if (cur != null && cur.getTag() instanceof String) focusKey = (String) cur.getTag();
        // Removing the focused tile would hand focus to a pill; park them until we refocus.
        // (If a pill holds focus, leave it there: the rebuild doesn't touch it.)
        View onPill = wifiPill.hasFocus() ? wifiPill : inputsPill.hasFocus() ? inputsPill
                : projectorPill.hasFocus() ? projectorPill : settingsPill.hasFocus() ? settingsPill : null;
        if (onPill == null) {
            wifiPill.setFocusable(false);
            inputsPill.setFocusable(false);
            projectorPill.setFocusable(false);
            settingsPill.setFocusable(false);
        }
        rows.removeAllViews();
        rowOf.clear();

        boolean entrance = !shownOnce && cfg.motion != Prefs.MOTION_OFF;
        shownOnce = true;
        LinearLayout row = null;
        View toFocus = null;
        for (int i = 0; i < apps.size(); i++) {
            AppInfo a = apps.get(i);
            int r = i / cols;
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams lp;
                if (r == 0) {
                    // the dock row sits inside the band (drawn behind it by its own view)
                    row.setPadding(px(BAND_PAD), px(BAND_PAD), px(BAND_PAD), px(BAND_PAD));
                    lp = new LinearLayout.LayoutParams(px(dockW), px(dockH));
                    lp.bottomMargin = px(26);
                } else {
                    // the rest of the grid only appears once you go down from the dock
                    row.setPadding(px(BAND_PAD), 0, px(BAND_PAD), 0);
                    row.setAlpha(focusRow == 0 ? 0f : 1f);
                    lp = new LinearLayout.LayoutParams(px(dockW), px(rowH));
                }
                rows.addView(row, lp);
            }
            rowOf.put(a.key, r);
            View cell = tile(a, r == 0, i % cols == 0);
            row.addView(cell);
            View card = cell.findViewWithTag(a.key);
            if (a.key.equals(focusKey) || toFocus == null) toFocus = card;
            if (entrance) {
                cell.setAlpha(0f);
                cell.setTranslationY(px(22));
                cell.animate().alpha(1f).translationY(0).setStartDelay(cfg.ms(25) * i)
                        .setDuration(cfg.ms(380)).setInterpolator(EASE).start();
            }
        }
        // content is taller than the screen once there are several rows; size it exactly so lower
        // rows are laid out (a wrap_content child of the root is capped at the screen height)
        int nRows = (apps.size() + cols - 1) / cols;
        content.getLayoutParams().height = px(dockH + 26 + Math.max(0, nRows - 1) * rowH + 160);
        content.requestLayout();
        if (apps.isEmpty()) {
            showShelf(null);
            slideTo(0, true);
        }
        View target = toFocus;
        rows.post(() -> {
            boolean ccOpen = panel.isOpen();
            if (!ccOpen) {
                wifiPill.setFocusable(true);
                inputsPill.setFocusable(true);
                projectorPill.setFocusable(true);
                settingsPill.setFocusable(true);
            }
            if (onPill != null) onPill.requestFocus();
            else if (target != null && !ccOpen) target.requestFocus();
            else if (!ccOpen) wifiPill.requestFocus(); // no tile left to focus (grid emptied): fall back to a pill
            slideTo(focusRow, true); // sizes may have changed with the settings: re-place the dock or grid
            if (moving != null) startWiggle();
        });
    }

    private View tile(AppInfo a, boolean dock, boolean firstInRow) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(cardW), -1);
        if (!firstInRow) clp.leftMargin = px(gap);
        cell.setLayoutParams(clp);

        FrameLayout card = new FrameLayout(this);
        card.setTag(a.key);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipChildren(false);
        // The selection lives in each tile: a soft shadow behind the artwork and a bright rim around it,
        // both hidden until the tile is selected. They grow with the tile and only ever fade.
        ImageView shade = new ImageView(this);
        shade.setImageDrawable(new BitmapDrawable(getResources(), focusShadow()));
        shade.setAlpha(0f);
        shade.forceHasOverlappingRendering(false); // fades without an offscreen layer
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        int sp = px(SHADOW_PAD);
        slp.setMargins(-sp, -sp, -sp, -sp);
        card.addView(shade, slp);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_XY); // both copies are pre-sized: 1:1 at rest and when lifted
        art.setImageDrawable(artFor(a));
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        View rim = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(radius + 2));
        ring.setStroke(px(3), 0xFFFFFFFF);
        rim.setBackground(ring);
        rim.setAlpha(0f);
        rim.forceHasOverlappingRendering(false); // fades without an offscreen layer
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-1, -1);
        rlp.setMargins(-px(2), -px(2), -px(2), -px(2)); // 2 dp outside, 1 dp over the soft edge: no seam
        card.addView(rim, rlp);

        TextView label = new TextView(this);
        label.setText(a.label);
        label.setTypeface(Fonts.regular(this));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextSize(13);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, px(14), 0, 0);
        // the dock names the focused app in the shelf title instead of under the tile
        boolean showLabels = !dock && cfg.labels != Prefs.LABELS_NEVER;
        label.setVisibility(showLabels ? View.VISIBLE : View.GONE);
        label.setAlpha(cfg.labels == Prefs.LABELS_ALWAYS ? 0.6f : 0f);

        card.setOnFocusChangeListener((v, has) -> onCardFocus(v, art, shade, rim, label, a, has));
        card.setOnClickListener(v -> onCardClick(v, a));
        card.setOnLongClickListener(v -> { if (moving == null) startMove(a); return true; }); // hold OK: jiggle to move
        card.setOnKeyListener((v, code, e) -> onCardKey(a, code, e));

        cell.addView(card, new LinearLayout.LayoutParams(px(cardW), px(cardH)));
        cell.addView(label, new LinearLayout.LayoutParams(px(cardW), -2));
        return cell;
    }

    private void onCardFocus(View card, ImageView art, View shade, View rim, TextView label, AppInfo a, boolean has) {
        float sx = has ? liftX : 1f, sy = has ? liftY : 1f;
        // the lifted copy, squeezed into the resting tile and scaled back up by the lift, lands 1:1 on screen pixels
        art.setImageDrawable(artFor(has && a.artLift != null ? a.artLift : a.art));
        long d = cfg.ms(has ? 200 : 140);
        card.animate().scaleX(sx).scaleY(sy).setDuration(d).setInterpolator(EASE).start();
        // the old tile lets go and the new one lights up in place: nothing travels between them
        rim.animate().alpha(has ? 1f : 0f).setDuration(d).setInterpolator(EASE).start();
        shade.animate().alpha(has ? 1f : 0f).setDuration(d).setInterpolator(EASE).start();
        float off = cfg.labels == Prefs.LABELS_ALWAYS ? 0.6f : 0f;
        label.animate().alpha(has ? 1f : off).translationY(has ? px(6) : 0).setDuration(cfg.ms(200)).setInterpolator(EASE).start();
        if (!has) return;
        focusKey = a.key;
        Integer r = rowOf.get(a.key);
        int row = r == null ? 0 : r;
        slideTo(row, false);
        showShelf(row == 0 ? a : null);
    }

    /**
     * Arrow keys move strictly through the grid: LEFT/RIGHT stay in the row and stop at its ends,
     * DOWN/UP keep the column, and only UP from the dock reaches the pills. (Android's own focus
     * search picks the "nearest" view, which could jump from a tile to a pill.)
     */
    private boolean onCardKey(AppInfo a, int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            // MENU: hide, app info, uninstall (also from jiggle mode, which it ends first)
            if (e.getAction() == KeyEvent.ACTION_UP) {
                AppInfo target = moving != null ? moving : a;
                if (moving != null) endMove();
                options(target);
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (moving != null) {
            // the OK still held from the long press repeats; only a fresh press ends jiggle mode
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (ok && e.getRepeatCount() > 0) return true;
            return moveKey(code);
        }
        int i = apps.indexOf(a), n = apps.size(), col = i % cols, row = i / cols, lastRow = (n - 1) / cols;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) focusApp(i - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < cols - 1 && i + 1 < n) focusApp(i + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) {
                    // Land on the pill roughly above the column you're in, not always Wi-Fi.
                    View[] pillsByCol = {wifiPill, inputsPill, projectorPill, settingsPill};
                    pillsByCol[Math.min(col, pillsByCol.length - 1)].requestFocus();
                } else focusApp(Math.min((row - 1) * cols + col, n - 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < lastRow) focusApp(Math.min((row + 1) * cols + col, n - 1));
                return true;
        }
        return false;
    }

    private void focusApp(int index) {
        View card = rows.findViewWithTag(apps.get(index).key);
        if (card != null) card.requestFocus();
    }

    /** Focuses the last selected tile (or the first), e.g. coming down from the pills. */
    private void focusLastCard() {
        View card = focusKey == null ? null : rows.findViewWithTag(focusKey);
        if (card == null && !apps.isEmpty()) card = rows.findViewWithTag(apps.get(0).key);
        if (card != null) card.requestFocus();
    }

    private void onCardClick(View card, AppInfo a) {
        if (moving != null) { endMove(); return; }
        if (cfg.motion == Prefs.MOTION_FULL) {
            card.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).setInterpolator(EASE)
                    .withEndAction(() -> card.animate().scaleX(liftX).scaleY(liftY).setDuration(180).setInterpolator(EASE).start())
                    .start();
        }
        try {
            startActivity(a.launch);
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- motion: slide, shelf --------------------------------------------------------

    /**
     * Two modes. Dock: only the first row shows, low on the screen in its band, under the app's name.
     * Grid (any lower row focused): the band fades away, the whole grid fades in and slides up, and
     * it scrolls further only when the focused row would leave the screen.
     */
    private void slideTo(int row, boolean force) {
        if (row == focusRow && !force) return;
        boolean modeChange = (row == 0) != (focusRow == 0) || force;
        focusRow = row;
        long d = cfg.ms(420);
        float y;
        if (row == 0) {
            y = px(dockRest);
        } else {
            float screenH = getResources().getDisplayMetrics().heightPixels;
            float rowBottom = px(dockH + 26) + row * px(rowH); // bottom of the focused row's slot
            y = Math.min(px(DOCK_UP), screenH - px(24) - rowBottom);
        }
        if (d == 0) content.setTranslationY(y);
        else content.animate().translationY(y).setDuration(d).setInterpolator(EASE).start();
        if (!modeChange) return;
        float gridAlpha = row == 0 ? 0f : 1f;
        topFade.animate().alpha(gridAlpha).setDuration(cfg.ms(240)).start();
        band.animate().alpha(1f - gridAlpha).setDuration(cfg.ms(row == 0 ? 360 : 240)).start();
        for (int r = 1; r < rows.getChildCount(); r++) {
            View v = rows.getChildAt(r);
            v.animate().alpha(gridAlpha).setStartDelay(row == 0 ? 0 : cfg.ms(60) * r)
                    .setDuration(cfg.ms(row == 0 ? 200 : 360)).setInterpolator(EASE).start();
        }
        if (row != 0) showShelf(null);
    }

    private void resizeDock() {
        shadowBmp = null; // tiles changed size: the next grid gets a fresh shadow
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) band.getLayoutParams();
        blp.height = px(dockH);
        blp.width = px(dockW);
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) shelfTitle.getLayoutParams();
        tlp.leftMargin = px(dockLeft() + 4);
        tlp.topMargin = px(dockRest - 98);
        shelfTitle.requestLayout();
        band.setLayoutParams(blp);
        ((GradientDrawable) ((android.graphics.drawable.LayerDrawable) band.getBackground()).getDrawable(1)).setCornerRadius(px(radius + BAND_PAD));
        bandTint.setCornerRadius(px(radius + BAND_PAD));
    }

    /** Names the focused dock app above the dock over its ambient glow; null hides both. */
    private void showShelf(AppInfo a) {
        long d = cfg.ms(380);
        if (a == null) {
            shelfApp = null;
            shelfTitle.animate().alpha(0f).setDuration(cfg.ms(200)).start();
            glowBox.animate().alpha(0f).setDuration(d).start();
            return;
        }
        boolean wasHidden = shelfApp == null;
        if (a == shelfApp) return;
        shelfApp = a;
        if (wasHidden) {
            shelfTitle.setText(a.label);
            shelfTitle.setTranslationY(px(8));
            shelfTitle.animate().alpha(1f).translationY(0).setDuration(d).setInterpolator(EASE).start();
        } else {
            shelfTitle.animate().alpha(0f).setDuration(cfg.ms(110)).withEndAction(() -> {
                if (shelfApp == null) return;
                shelfTitle.setText(shelfApp.label);
                shelfTitle.animate().alpha(1f).setDuration(cfg.ms(260)).setInterpolator(EASE).start();
            }).start();
        }
        if (!cfg.tint) { glowBox.animate().alpha(0f).setDuration(d).start(); return; }
        // the light fades in once, then only its colour glides from app to app
        if (glowFade != null) glowFade.cancel();
        if (glowBox.getAlpha() == 0f || d == 0) tintGlow(a.glowColor);
        else {
            glowFade = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, a.glowColor);
            glowFade.setDuration(cfg.ms(360));
            glowFade.addUpdateListener(va -> tintGlow((int) va.getAnimatedValue()));
            glowFade.start();
        }
        glowBox.animate().alpha(1f).setDuration(cfg.ms(600)).start();
    }

    // ---- Control Center hooks --------------------------------------------------------------

    /** While the Control Center is open, nothing behind it can take focus. */
    void setBackgroundFocusable(boolean on) {
        content.setDescendantFocusability(on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        // Settings first: disabling the focused Wi-Fi pill first would pass focus to (and light) Settings.
        settingsPill.setFocusable(on);
        projectorPill.setFocusable(on);
        inputsPill.setFocusable(on);
        wifiPill.setFocusable(on);
    }

    void startSettings(String action) {
        // This OEM build has no standalone Settings app for the generic action — go
        // straight to the real Android TV Settings app, independent of the OEM launcher.
        Intent i = new Intent().setComponent(
                new android.content.ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) == null) {
            i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (i.resolveActivity(getPackageManager()) == null) {
            i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try { startActivity(i); } catch (Exception ignored) { }
    }

    /** Allwinner's own input/source switcher (HDMI, AV, etc.) — independent app, not the OEM launcher. */
    void startInputs() {
        Intent i = new Intent().setComponent(
                new android.content.ComponentName("com.softwinner.awsource", "com.softwinner.awsource.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) != null) {
            try { startActivity(i); return; } catch (Exception ignored) { }
        }
        Intent fallback = getPackageManager().getLaunchIntentForPackage("com.softwinner.awsource");
        if (fallback != null) { try { startActivity(fallback); return; } catch (Exception ignored) { } }
        Toast.makeText(this, "Input switcher not found", Toast.LENGTH_SHORT).show();
    }

    /**
     * The OEM's projection-specific controls (keystone/trapezoidal correction, screen size,
     * focus) — the one piece genuinely proprietary to this Allwinner H726 hardware. The real
     * screen (ProjectActivity) has no exported intent-filter (default-false on this targetSdk),
     * so a third-party app can't launch it directly; this opens the OEM's settings hub instead,
     * where Projection is the first tile.
     */
    void startProjector() {
        String[] pkgs = {"com.htc.luminaos", "com.htc.magcubicos"};
        for (String pkg : pkgs) {
            Intent i = new Intent().setComponent(
                    new android.content.ComponentName(pkg, pkg + ".activity.settings.MainSettingActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) != null) {
                try { startActivity(i); return; } catch (Exception ignored) { }
            }
        }
        for (String pkg : pkgs) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { try { startActivity(i); return; } catch (Exception ignored) { } }
        }
        Toast.makeText(this, "Projector settings not found", Toast.LENGTH_SHORT).show();
    }

    // ---- options and move mode ---------------------------------------------------------------

    private void options(AppInfo a) {
        List<String> items = new ArrayList<>(Arrays.asList("Move", "Hide", "App info"));
        if (!a.system) items.add("Uninstall");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    switch (items.get(w)) {
                        case "Move": startMove(a); break;
                        case "Hide":
                            Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
                            h.add(a.key);
                            prefs.edit().putStringSet("hidden", h).apply();
                            Toast.makeText(this, a.label + " hidden. Launcher settings can show it again.", Toast.LENGTH_LONG).show();
                            load();
                            break;
                        case "App info":
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", a.key, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception ignored) { }
                            break;
                        case "Uninstall":
                            try {
                                startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.key, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception ignored) { }
                            break;
                    }
                }).show();
    }

    private void startMove(AppInfo picked) {
        AppInfo a = byKey(apps, picked.key); // the list may have reloaded while the dialog was open
        if (a == null) return;
        moving = a;
        focusKey = a.key;
        setPillsLocked(true);
        startWiggle();
        Toast.makeText(this, "Move " + a.label + " with the arrows · OK when done · MENU for more", Toast.LENGTH_LONG).show();
    }

    /** While a tile is being dragged, the pills must not steal focus or a tap on them. */
    private void setPillsLocked(boolean locked) {
        boolean enabled = !locked;
        wifiPill.setFocusable(enabled);
        inputsPill.setFocusable(enabled);
        projectorPill.setFocusable(enabled);
        settingsPill.setFocusable(enabled);
        wifiPill.setClickable(enabled);
        inputsPill.setClickable(enabled);
        projectorPill.setClickable(enabled);
        settingsPill.setClickable(enabled);
    }

    /** Every tile jiggles; the one being moved jiggles most. Restarted after each rebuild of the grid. */
    private void startWiggle() {
        if (moving == null || cfg.motion == Prefs.MOTION_OFF) return;
        if (jiggle == null) jiggle = new Jiggle(dp);
        List<View> cards = new ArrayList<>();
        for (AppInfo a : apps) {
            View c = rows.findViewWithTag(a.key);
            if (c != null) cards.add(c);
        }
        jiggle.start(cards, rows.findViewWithTag(moving.key));
    }

    private boolean moveKey(int code) {
        int i = apps.indexOf(moving), last = apps.size() - 1;
        if (i < 0) { endMove(); return true; }
        int j = i, col = i % cols;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (col == 0) return true; j = i - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (col == cols - 1 || i == last) return true; j = i + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: j = i - cols; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: j = i + cols; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                endMove();
                return true;
            default:
                return false; // volume and other keys still work while moving
        }
        if (j < 0 || j > last) return true; // refuse the move at a row/grid edge instead of jumping to it
        if (j != i) {
            apps.add(j, apps.remove(i));
            focusKey = moving.key;
            buildGrid();
        }
        return true;
    }

    private void endMove() {
        if (jiggle != null) jiggle.stop();
        moving = null;
        setPillsLocked(false);
        saveOrder();
    }

    static final int SHADOW_PAD = 24; // dp of room around a tile for its blurred shadow

    /** The selected tile's drop shadow: blurred once per tile size, shared by every tile. */
    android.graphics.Bitmap focusShadow() {
        if (shadowBmp != null) return shadowBmp;
        int pad = px(SHADOW_PAD), w = px(cardW) + 2 * pad, h = px(cardH) + 2 * pad;
        shadowBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xCC000000);
        p.setMaskFilter(new android.graphics.BlurMaskFilter(px(12), android.graphics.BlurMaskFilter.Blur.NORMAL));
        float drop = px(8);
        new android.graphics.Canvas(shadowBmp).drawRoundRect(pad - px(2), pad + drop, w - pad + px(2), h - pad + drop,
                px(radius), px(radius), p);
        return shadowBmp;
    }

    /** Left edge of the centred dock band, in dp. */
    float dockLeft() {
        float screenW = getResources().getDisplayMetrics().widthPixels / dp;
        return Math.max(SIDE, (screenW - dockW) / 2f);
    }

    int px(float v) { return Math.round(v * dp); }
}
