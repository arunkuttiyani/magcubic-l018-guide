package dev.lite.home;

import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Jiggle mode, iOS style: every tile wobbles a little, each slightly out of step, and the tile being
 * moved wobbles more. Driven frame by frame from the display's vsync rather than by Animators, so it
 * keeps going even when the system's animator duration scale is 0 (which freezes ordinary animations
 * on their last frame). Only rotation and translation change: no tile redraws.
 */
final class Jiggle implements Choreographer.FrameCallback {

    private final List<View> tiles = new ArrayList<>();
    private final List<Float> phases = new ArrayList<>();
    private View lead;
    private long start;
    private boolean running;
    private final float bob;

    Jiggle(float dp) {
        bob = 1.2f * dp;
    }

    /** Starts (or restarts, after the grid was rebuilt) with these tiles; lead is the one being moved. */
    void start(List<View> views, View leadTile) {
        stopViews();
        tiles.clear();
        phases.clear();
        for (int i = 0; i < views.size(); i++) {
            tiles.add(views.get(i));
            phases.add((float) ((i * 2.399) % (2 * Math.PI))); // golden-angle offsets: no two in step
        }
        lead = leadTile;
        if (!running) {
            running = true;
            start = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        stopViews();
        tiles.clear();
        phases.clear();
        lead = null;
    }

    private void stopViews() {
        for (View v : tiles) {
            v.setRotation(0);
            v.setTranslationY(0);
        }
    }

    @Override public void doFrame(long nanos) {
        if (!running) return;
        if (start == 0) start = nanos;
        double t = (nanos - start) / 1e9;
        for (int i = 0; i < tiles.size(); i++) {
            View v = tiles.get(i);
            boolean isLead = v == lead;
            double w = 2 * Math.PI * (isLead ? 4.2 : 3.4);          // wobbles per second
            double s = Math.sin(t * w + phases.get(i));
            v.setRotation((float) (s * (isLead ? 2.6 : 1.3)));      // degrees
            v.setTranslationY((float) (Math.cos(t * w * 0.5 + phases.get(i)) * (isLead ? bob : bob * 0.5)));
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}
