package dev.lite.shield;

import java.util.Arrays;

/** A growable array of longs (no boxing: a million Long objects would not fit a TV's heap comfortably). */
final class Growing {
    private long[] a = new long[1 << 16];
    private int n;

    void add(long v) {
        if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
        a[n++] = v;
    }

    long[] sortedUnique() {
        long[] t = Arrays.copyOf(a, n);
        Arrays.sort(t);
        int w = 0;
        for (int i = 0; i < t.length; i++) if (i == 0 || t[i] != t[i - 1]) t[w++] = t[i];
        return Arrays.copyOf(t, w);
    }
}
