package za.co.mrhdigital.audex.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Allows at most {@code max} failures per key inside a sliding window, then blocks until the oldest one ages out.
 * Held in memory, which is right for a single server; move it to the database if the API is ever scaled out.
 */
public final class AttemptLimiter {
    private final int max;
    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public AttemptLimiter(int max, long windowMillis, LongSupplier clock) {
        this.max = max; this.windowMillis = windowMillis; this.clock = clock;
    }

    /** Minutes the caller must wait, or 0 when another attempt is allowed. */
    public int minutesBlocked(String key) {
        Deque<Long> q = failures.get(key);
        if (q == null) return 0;
        synchronized (q) {
            prune(q);
            if (q.size() < max) return 0;
            long waitMs = q.peekFirst() + windowMillis - clock.getAsLong();
            return (int) Math.max(1, (waitMs + 59_999) / 60_000);
        }
    }

    public void recordFailure(String key) {
        Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) { prune(q); q.addLast(clock.getAsLong()); }
    }

    public void reset(String key) { failures.remove(key); }

    private void prune(Deque<Long> q) {
        long cutoff = clock.getAsLong() - windowMillis;
        while (!q.isEmpty() && q.peekFirst() <= cutoff) q.pollFirst();
    }
}
