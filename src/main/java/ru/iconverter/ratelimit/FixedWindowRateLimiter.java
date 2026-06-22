package ru.iconverter.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight in-memory fixed-window rate limiter, keyed by an arbitrary string
 * (e.g. client IP). No external dependencies: a {@link ConcurrentHashMap} of
 * per-key windows that reset every {@code windowMs}. Good enough to protect the
 * heavy conversion endpoints from abuse / runaway loops; not a distributed limiter.
 */
public class FixedWindowRateLimiter {

    private final int maxPerWindow;
    private final long windowMs;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    /** @return true if the request is allowed, false if the key is over its quota. */
    public boolean allow(String key, long now) {
        // Reset the window if it has expired; otherwise keep the running counter.
        Window w = windows.compute(key, (k, cur) ->
                (cur == null || now - cur.start >= windowMs) ? new Window(now) : cur);
        boolean allowed = w.count.incrementAndGet() <= maxPerWindow;
        if (windows.size() > MAX_TRACKED_KEYS) evictExpired(now);
        return allowed;
    }

    public int size() {
        return windows.size();
    }

    // Cap memory: if a flood of unique keys arrives, drop windows that already expired.
    private static final int MAX_TRACKED_KEYS = 10_000;

    private void evictExpired(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().start >= windowMs);
    }

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long start) { this.start = start; }
    }
}
