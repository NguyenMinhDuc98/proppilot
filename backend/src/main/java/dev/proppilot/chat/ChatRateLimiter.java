package dev.proppilot.chat;

import dev.proppilot.config.ChatProperties;
import dev.proppilot.limit.DailyQuota;
import dev.proppilot.limit.SlidingWindowLimiter;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Per-client sliding window (one minute) plus a global daily cap that protects the LLM budget. */
@Component
public class ChatRateLimiter {

    public enum Decision { ALLOWED, RATE_LIMITED, DAILY_LIMIT_REACHED }

    private final SlidingWindowLimiter perClient;
    private final DailyQuota daily;

    public ChatRateLimiter(ChatProperties props, Clock clock) {
        this.perClient = new SlidingWindowLimiter(props.rateLimitPerMinute(), clock);
        this.daily = new DailyQuota(props.dailyLimit(), clock);
    }

    /** Only a question that is let through uses up the daily budget. */
    public synchronized Decision tryAcquire(String clientKey) {
        if (!daily.tryTake()) {
            return Decision.DAILY_LIMIT_REACHED;
        }
        if (!perClient.tryAcquire(clientKey)) {
            daily.giveBack();
            return Decision.RATE_LIMITED;
        }
        return Decision.ALLOWED;
    }
}
