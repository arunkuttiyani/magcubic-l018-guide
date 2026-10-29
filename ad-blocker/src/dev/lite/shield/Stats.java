package dev.lite.shield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Counters for the Simple Ad Blocker screen, kept in memory only (nothing is written to disk). */
final class Stats {
    private Stats() {}

    static volatile boolean running;
    static final AtomicLong lookups = new AtomicLong(), blocked = new AtomicLong();
    private static final ArrayDeque<String> recent = new ArrayDeque<>();

    static void blocked(String name) {
        blocked.incrementAndGet();
        synchronized (recent) {
            recent.remove(name);
            recent.addFirst(name);
            while (recent.size() > 12) recent.removeLast();
        }
    }

    /** The last few distinct blocked names, newest first: handy to spot something blocked by mistake. */
    static List<String> recent() {
        synchronized (recent) { return new ArrayList<>(recent); }
    }
}
