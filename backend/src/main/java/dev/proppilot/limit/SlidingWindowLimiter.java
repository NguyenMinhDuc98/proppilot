package dev.proppilot.limit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** At most {@code maxPerMinute} hits per client key in any sliding one-minute window. */
public class SlidingWindowLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int PRUNE_THRESHOLD = 10_000;

    private final int maxPerMinute;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> hitsByClient = new HashMap<>();

    public SlidingWindowLimiter(int maxPerMinute, Clock clock) {
        this.maxPerMinute = maxPerMinute;
        this.clock = clock;
    }

    /** Counts the hit and returns true, or returns false (counting nothing) when the client is over its limit. */
    public synchronized boolean tryAcquire(String clientKey) {
        long now = clock.millis();
        if (hitsByClient.size() > PRUNE_THRESHOLD) {
            hitsByClient.values().removeIf(hits -> hits.isEmpty() || now - hits.peekLast() > WINDOW_MILLIS);
        }
        var hits = hitsByClient.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        while (!hits.isEmpty() && now - hits.peekFirst() > WINDOW_MILLIS) {
            hits.pollFirst();
        }
        if (hits.size() >= maxPerMinute) {
            return false;
        }
        hits.addLast(now);
        return true;
    }
}
