package dev.lite.home;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One tile on the home screen. Artwork is prepared off the main thread by HomeActivity.queryApps(). */
final class AppInfo {
    String key, label;
    Drawable banner;        // 16:9 TV banner, or null
    Drawable icon;          // square icon, used when there is no banner
    int[] washColors;       // gradient behind square icons
    int tint;               // dark accent colour taken from the artwork
    int glowColor;                // the artwork's most vivid colour, for the ambient glow (Art.vividColor)
    android.graphics.Bitmap art;      // the tile's artwork at its exact resting size
    android.graphics.Bitmap artLift;  // the same at the selected (lifted) size
    Intent launch;
    boolean system;
}
