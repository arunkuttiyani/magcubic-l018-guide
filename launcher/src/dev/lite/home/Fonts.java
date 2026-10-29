package dev.lite.home;

import android.content.Context;
import android.graphics.Typeface;

/** Outfit (SIL Open Font License, see FONT-LICENSE-OFL.txt), loaded once from assets. */
final class Fonts {
    private Fonts() {}

    private static Typeface light, regular;

    static Typeface light(Context c) {
        if (light == null) light = load(c, "fonts/Outfit-Light.ttf", "sans-serif-light");
        return light;
    }

    static Typeface regular(Context c) {
        if (regular == null) regular = load(c, "fonts/Outfit-Regular.ttf", "sans-serif");
        return regular;
    }

    private static Typeface load(Context c, String asset, String fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), asset);
        } catch (Exception e) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }
}
