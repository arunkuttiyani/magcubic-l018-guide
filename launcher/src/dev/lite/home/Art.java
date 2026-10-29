package dev.lite.home;

import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/** Artwork helpers: colour sampling and flat tiles for apps that only ship a square icon. */
final class Art {
    private Art() {}

    /** A dark, saturated version of a drawable's dominant colour. */
    static int accentOf(Drawable d) {
        float[] hsv = dominantHsv(d);
        if (hsv == null) return 0xFF1B2433;
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = 0.28f;
        return Color.HSVToColor(hsv);
    }

    /** Gradient colours behind a square icon, from the icon's own colour (slate for greyscale icons). */
    static int[] washColors(Drawable icon) {
        float[] hsv = dominantHsv(icon);
        if (hsv == null || hsv[1] < 0.2f) hsv = new float[]{222, 0.35f, 0};
        hsv[1] = Math.min(0.85f, hsv[1]);
        hsv[2] = 0.62f;
        int light = Color.HSVToColor(hsv);
        hsv[2] = 0.22f;
        return new int[]{light, Color.HSVToColor(hsv)};
    }

    /** Square icon centred on its colour wash, sized for a w x h tile (px). New instance each call. */
    static Drawable iconTile(Drawable icon, int[] wash, int w, int h, int iconSize, float radius) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, wash);
        bg.setCornerRadius(radius);
        Drawable glyph = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bg, glyph});
        int ix = (w - iconSize) / 2, iy = (h - iconSize) / 2;
        ld.setLayerInset(1, ix, iy, ix, iy);
        return ld;
    }

    /**
     * The sharpest copy of an activity's banner (or icon) the app ships: asks for the xxxhdpi
     * version instead of the screen's own density, so tiles are drawn down from more pixels, not up.
     * Null if the app has none.
     */
    static Drawable hiRes(PackageManager pm, ActivityInfo ai, boolean banner) {
        int id = banner ? (ai.banner != 0 ? ai.banner : ai.applicationInfo.banner) : ai.getIconResource();
        if (id == 0) return null;
        try {
            Resources r = pm.getResourcesForApplication(ai.applicationInfo);
            return r.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Renders a drawable to exactly w x h pixels, centre-cropped, from its full resolution: halving
     * in filtered steps keeps detail and avoids the shimmer of one big bilinear shrink.
     */
    static Bitmap sharp(Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
        float s = Math.max((float) w / iw, (float) h / ih);   // cover the tile
        int cw = Math.max(w, Math.round(iw * s)), ch = Math.max(h, Math.round(ih * s));
        float k = Math.max(1f, Math.min(4f, 1f / s));          // source pixels per tile pixel, at most 4
        Bitmap b = Bitmap.createBitmap(Math.round(cw * k), Math.round(ch * k), Bitmap.Config.ARGB_8888);
        Drawable c = copy(d);
        c.setBounds(0, 0, b.getWidth(), b.getHeight());
        c.draw(new Canvas(b));
        while (b.getWidth() >= cw * 2) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        if (b.getWidth() != cw || b.getHeight() != ch) {
            Bitmap fit = Bitmap.createScaledBitmap(b, cw, ch, true);
            b.recycle();
            b = fit;
        }
        if (cw == w && ch == h) return b;
        Bitmap crop = Bitmap.createBitmap(b, (cw - w) / 2, (ch - h) / 2, w, h);
        if (crop != b) b.recycle();
        return crop;
    }

    /** A tile for apps with only a square icon: CleanMyTV's graphite card with the icon crisp in the middle. */
    static Bitmap iconCard(Drawable icon, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1B1B1E, 0xFF0E0E10});
        bg.setBounds(0, 0, w, h);
        bg.draw(c);
        int size = Math.round(h * 0.62f);
        Bitmap glyph = sharp(icon, size, size);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(glyph, (w - size) / 2f, (h - size) / 2f, p);
        glyph.recycle();
        return out;
    }

    /**
     * The colour the ambient glow takes: the most saturated, not-too-dark pixel of the artwork; for an
     * almost colourless banner, its average lifted to mid brightness.
     * Adapted from CleanMyTV's AppAdapter.vividColor (github.com/Supopo/CleanMyTV, MIT, (c) 2026 Gathin).
     */
    static int vividColor(Bitmap art) {
        if (art == null) return 0xFF444444;
        int n = 12;
        Bitmap small = Bitmap.createScaledBitmap(art, n, n, true);
        int[] px = new int[n * n];
        small.getPixels(px, 0, n, 0, 0, n, n);
        if (small != art) small.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr; g += pg; b += pb;
            int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float score = sat * Math.min(1f, max / 255f * 1.4f); // saturated and not too dark
            if (score > bestScore) { bestScore = score; best = p; }
        }
        if (bestScore < 0.25f) {
            Color.colorToHSV(Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n))), hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /**
     * The tile's artwork with its rounded corners painted in, antialiased. Clipping a scaled view to a
     * rounded outline left jagged, flickering corners on this GPU while the tile grew on focus.
     */
    static Bitmap rounded(Bitmap src, float radius) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(0, 0, src.getWidth(), src.getHeight(), radius, radius, p);
        src.recycle();
        return out;
    }

    /** A second, independent copy of a drawable (a Drawable can only live in one view). */
    static Drawable copy(Drawable d) {
        return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
    }

    // Dominant colour (HSV), weighted towards vivid pixels; null if the drawable is empty.
    private static float[] dominantHsv(Drawable d) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        Drawable c = copy(d);
        c.setBounds(0, 0, 16, 9);
        c.draw(new Canvas(b));
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, wsum = 0;
        for (int y = 0; y < 9; y++) for (int x = 0; x < 16; x++) {
            int p = b.getPixel(x, y);
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            double w = hsv[1] * hsv[2] + 0.02;
            r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; wsum += w;
        }
        b.recycle();
        if (wsum == 0) return null;
        Color.colorToHSV(Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum)), hsv);
        return hsv;
    }
}
