package dev.proppilot.chat;

import dev.proppilot.config.ChatProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Per-client sliding window (one minute) plus a global daily cap that protects the LLM budget. */
@Component
public class ChatRateLimiter {

    public enum Decision { ALLOWED, RATE_LIMITED, DAILY_LIMIT_REACHED }

    private static final long WINDOW_MILLIS = 60_000;
    private static final int PRUNE_THRESHOLD = 10_000;

    private final ChatProperties props;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> hitsByClient = new HashMap<>();
    private LocalDate today;
    private int hitsToday;

    public ChatRateLimiter(ChatProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public synchronized Decision tryAcquire(String clientKey) {
        long now = clock.millis();
        resetDayIfNeeded();
        if (hitsToday >= props.dailyLimit()) {
            return Decision.DAILY_LIMIT_REACHED;
        }
        if (hitsByClient.size() > PRUNE_THRESHOLD) {
            hitsByClient.values().removeIf(hits -> hits.isEmpty() || now - hits.peekLast() > WINDOW_MILLIS);
        }
        var hits = hitsByClient.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        while (!hits.isEmpty() && now - hits.peekFirst() > WINDOW_MILLIS) {
            hits.pollFirst();
        }
        if (hits.size() >= props.rateLimitPerMinute()) {
            return Decision.RATE_LIMITED;
        }
        hits.addLast(now);
        hitsToday++;
        return Decision.ALLOWED;
    }

    private void resetDayIfNeeded() {
        var date = LocalDate.now(clock);
        if (!date.equals(today)) {
            today = date;
            hitsToday = 0;
        }
    }
}
