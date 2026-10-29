package dev.lite.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Wi-Fi / Ethernet / offline glyph drawn with a few strokes; recolourable for focused (white) tiles. */
class NetworkIcon extends View {

    static final int OFFLINE = 0, WIFI = 1, ETHERNET = 2;

    private int type = OFFLINE, level;     // level: Wi-Fi bars lit, 0..3
    private boolean validated = true;      // false = connected but no internet
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    NetworkIcon(Context c) {
        super(c);
        for (Paint p : new Paint[]{on, off}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        setColor(0xFFFFFFFF);
    }

    void setColor(int color) {
        on.setColor(color);
        off.setColor((color & 0x00FFFFFF) | 0x40000000);
        invalidate();
    }

    void set(int type, int level, boolean validated) {
        if (type == this.type && level == this.level && validated == this.validated) return;
        this.type = type;
        this.level = level;
        this.validated = validated;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        float sw = s * 0.09f;
        on.setStrokeWidth(sw);
        off.setStrokeWidth(sw);
        float cx = w / 2, by = h / 2 + s * 0.32f;

        if (type == ETHERNET) {
            r.set(cx - s * 0.32f, h / 2 - s * 0.22f, cx + s * 0.32f, h / 2 + s * 0.26f);
            c.drawRoundRect(r, sw, sw, on);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * s * 0.14f;
                c.drawLine(x, h / 2 - s * 0.05f, x, h / 2 + s * 0.1f, on);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                float rad = s * (0.2f + 0.16f * i);
                r.set(cx - rad, by - rad, cx + rad, by + rad);
                c.drawArc(r, 225, 90, false, type == WIFI && level > i ? on : off);
            }
            on.setStyle(Paint.Style.FILL);
            off.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, by - sw * 0.2f, sw * 0.9f, type == WIFI ? on : off);
            on.setStyle(Paint.Style.STROKE);
            off.setStyle(Paint.Style.STROKE);
            if (type == OFFLINE) c.drawLine(cx - s * 0.36f, by - s * 0.62f, cx + s * 0.36f, by + s * 0.02f, on);
        }
        if (type != OFFLINE && !validated) {
            float bx = w - sw * 1.2f, top = h / 2 - s * 0.36f;
            c.drawLine(bx, top, bx, top + s * 0.3f, on);
            on.setStyle(Paint.Style.FILL);
            c.drawCircle(bx, top + s * 0.44f, sw * 0.6f, on);
            on.setStyle(Paint.Style.STROKE);
        }
    }
}
