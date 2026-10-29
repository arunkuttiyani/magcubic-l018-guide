package dev.lite.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Weather glyph in the style of Google's weather icons: a glowing gradient sun or moon (by time of
 * day), a softly shaded cloud in front, and blue rain, snow or a lightning bolt underneath.
 * Drawn as vector shapes at the view's real pixel size, so it is sharp at any size. Every shape
 * stays inside a 6% margin, so nothing is clipped at the edges.
 */
class WeatherIcon extends View {

    private int kind = Weather.CLOUDY;
    private boolean day = true;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path(), tmp = new Path();

    WeatherIcon(Context c) {
        super(c);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int kind, boolean day) {
        if (kind == this.kind && day == this.day) return;
        this.kind = kind;
        this.day = day;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        boolean wet = kind == Weather.RAIN || kind == Weather.DRIZZLE || kind == Weather.SNOW || kind == Weather.STORM;
        boolean dark = kind == Weather.RAIN || kind == Weather.STORM;

        switch (kind) {
            case Weather.CLEAR:
                if (day) sun(c, s * 0.5f, s * 0.5f, s * 0.25f); else moon(c, s * 0.5f, s * 0.5f, s * 0.3f);
                break;
            case Weather.PARTLY:
                // sun or moon behind, upper right; cloud in front, lower left
                if (day) sun(c, s * 0.64f, s * 0.36f, s * 0.19f); else moon(c, s * 0.64f, s * 0.36f, s * 0.22f);
                cloud(c, s, -s * 0.06f, s * 0.1f, false);
                break;
            case Weather.FOG:
                cloud(c, s, 0, -s * 0.08f, false);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.07f);
                paint.setColor(0xFFB8C3D1);
                c.drawLine(s * 0.22f, s * 0.8f, s * 0.78f, s * 0.8f, paint);
                c.drawLine(s * 0.32f, s * 0.91f, s * 0.68f, s * 0.91f, paint);
                break;
            default:
                cloud(c, s, 0, wet ? -s * 0.1f : 0, dark);
        }

        paint.setShader(null);
        if (kind == Weather.RAIN || kind == Weather.DRIZZLE) {
            int n = kind == Weather.RAIN ? 3 : 2;
            for (int i = 0; i < n; i++) {
                float x = s * (n == 3 ? 0.36f + 0.15f * i : 0.43f + 0.15f * i);
                drop(c, x, s * (i % 2 == 0 ? 0.78f : 0.84f), s * (kind == Weather.RAIN ? 0.075f : 0.06f));
            }
        } else if (kind == Weather.SNOW) {
            for (int i = 0; i < 3; i++) flake(c, s * (0.35f + 0.15f * i), s * (i == 1 ? 0.86f : 0.79f), s * 0.06f);
        } else if (kind == Weather.STORM) {
            bolt(c, s);
        }
        c.restore();
    }

    // ---- shapes ----------------------------------------------------------------------------

    private void sun(Canvas c, float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        // soft glow
        paint.setShader(new RadialGradient(cx, cy, r * 1.45f, new int[]{0x66FFC83D, 0x00FFC83D},
                new float[]{0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.45f, paint);
        // disc: warm yellow, deeper toward the edge
        paint.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f,
                0xFFFFE27A, 0xFFFFA81F, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, paint);
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        tmp.reset();
        tmp.addCircle(cx + r * 0.5f, cy - r * 0.38f, r * 0.82f, Path.Direction.CW);
        path.op(tmp, Path.Op.DIFFERENCE);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFFF6D5, 0xFFE8CF7C, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
    }

    private void cloud(Canvas c, float s, float dx, float dy, boolean dark) {
        path.reset();
        path.addCircle(s * 0.36f + dx, s * 0.59f + dy, s * 0.165f, Path.Direction.CW);
        tmp.reset();
        tmp.addCircle(s * 0.54f + dx, s * 0.48f + dy, s * 0.22f, Path.Direction.CW);
        path.op(tmp, Path.Op.UNION);
        tmp.reset();
        tmp.addCircle(s * 0.71f + dx, s * 0.61f + dy, s * 0.145f, Path.Direction.CW);
        path.op(tmp, Path.Op.UNION);
        tmp.reset();
        tmp.addRoundRect(s * 0.2f + dx, s * 0.6f + dy, s * 0.86f + dx, s * 0.755f + dy, s * 0.08f, s * 0.08f, Path.Direction.CW);
        path.op(tmp, Path.Op.UNION);
        paint.setStyle(Paint.Style.FILL);
        float top = s * 0.26f + dy, bottom = s * 0.76f + dy;
        paint.setShader(dark
                ? new LinearGradient(0, top, 0, bottom, 0xFFC9D3DE, 0xFF8D99A8, Shader.TileMode.CLAMP)
                : new LinearGradient(0, top, 0, bottom, 0xFFFFFFFF, 0xFFD3DDE8, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
    }

    private void drop(Canvas c, float x, float y, float r) {
        path.reset();
        path.moveTo(x, y - r * 1.9f);
        path.cubicTo(x + r * 0.2f, y - r * 1.2f, x + r, y - r * 0.4f, x + r, y + r * 0.1f);
        path.arcTo(x - r, y - r * 0.9f, x + r, y + r * 1.1f, 0, 180, false);
        path.cubicTo(x - r, y - r * 0.4f, x - r * 0.2f, y - r * 1.2f, x, y - r * 1.9f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, y - r * 2, 0, y + r, 0xFF8FD0FF, 0xFF2F8CF0, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
    }

    private void flake(Canvas c, float x, float y, float r) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.38f);
        paint.setColor(0xFFEAF4FF);
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 3 * i;
            float ex = (float) Math.cos(a) * r, ey = (float) Math.sin(a) * r;
            c.drawLine(x - ex, y - ey, x + ex, y + ey, paint);
        }
    }

    private void bolt(Canvas c, float s) {
        path.reset();
        path.moveTo(s * 0.53f, s * 0.6f);
        path.lineTo(s * 0.4f, s * 0.8f);
        path.lineTo(s * 0.5f, s * 0.8f);
        path.lineTo(s * 0.44f, s * 0.94f);
        path.lineTo(s * 0.63f, s * 0.73f);
        path.lineTo(s * 0.53f, s * 0.73f);
        path.lineTo(s * 0.6f, s * 0.6f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, s * 0.6f, 0, s * 0.94f, 0xFFFFE27A, 0xFFFFA81F, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
    }
}
