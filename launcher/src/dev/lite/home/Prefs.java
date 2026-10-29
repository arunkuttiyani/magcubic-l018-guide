package dev.lite.home;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Launcher settings, stored in the same "home" preferences file as the app order and hidden apps.
 * Every option has a safe default, so a fresh install looks the same as before settings existed.
 */
final class Prefs {

    static final String FILE = "home";

    // keys
    static final String COLS = "cols", TILE = "tile", LABELS = "labels", TINT = "tint",
            MOTION = "motion", CLOCK = "clock", DATE = "date",
            WEATHER = "weather", CITY = "wx_city", LAT = "wx_lat", LON = "wx_lon";

    // values
    static final int LABELS_ALWAYS = 0, LABELS_FOCUSED = 1, LABELS_NEVER = 2;
    static final int MOTION_FULL = 0, MOTION_REDUCED = 1, MOTION_OFF = 2;
    static final int CLOCK_SYSTEM = 0, CLOCK_12 = 1, CLOCK_24 = 2;
    static final int TILE_SMALL = 0, TILE_MEDIUM = 1, TILE_LARGE = 2;

    final int cols, tile, labels, motion, clock;
    final boolean tint, date, weather;

    private Prefs(SharedPreferences p) {
        cols = p.getInt(COLS, 4); // CleanMyTV: four 180 dp tiles across a 1080p TV
        tile = p.getInt(TILE, TILE_MEDIUM);
        labels = p.getInt(LABELS, LABELS_FOCUSED); // v4: names under the selected tile only, CleanMyTV style
        motion = p.getInt(MOTION, MOTION_FULL);
        clock = p.getInt(CLOCK, CLOCK_SYSTEM);
        tint = p.getBoolean(TINT, true);
        date = p.getBoolean(DATE, true);
        weather = p.getBoolean(WEATHER, true);
    }

    static Prefs load(Context c) { return new Prefs(c.getSharedPreferences(FILE, Context.MODE_PRIVATE)); }

    /** Changes when any visual setting changes, so the home screen knows to rebuild. */
    String signature() {
        return cols + "/" + tile + "/" + labels + "/" + motion + "/" + clock + "/" + tint + "/" + date
                + "/" + weather;
    }

    /** Animation duration scaled by the motion setting. */
    long ms(long full) {
        return motion == MOTION_FULL ? full : motion == MOTION_REDUCED ? full * 3 / 5 : 0;
    }

    /** Preferred tile width in dp; the grid shrinks it if the columns don't fit the screen. */
    int tileWidthDp() {
        return tile == TILE_SMALL ? 156 : tile == TILE_LARGE ? 200 : 180; // CleanMyTV designs at 180 x 101
    }
}
