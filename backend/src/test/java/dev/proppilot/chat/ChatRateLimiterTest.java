package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.chat.ChatRateLimiter.Decision;
import dev.proppilot.config.ChatProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ChatRateLimiterTest {

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-03-15T10:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    @Test
    void blocksAfterPerMinuteLimitAndRecoversAfterTheWindow() {
        var limiter = new ChatRateLimiter(new ChatProperties(500, 3, 1000), clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.ALLOWED);
        }
        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.RATE_LIMITED);

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void clientsAreLimitedIndependently() {
        var limiter = new ChatRateLimiter(new ChatProperties(500, 1, 1000), clock);

        assertThat(limiter.tryAcquire("a")).isEqualTo(Decision.ALLOWED);
        assertThat(limiter.tryAcquire("b")).isEqualTo(Decision.ALLOWED);
        assertThat(limiter.tryAcquire("a")).isEqualTo(Decision.RATE_LIMITED);
    }

    @Test
    void dailyLimitAppliesToEveryoneAndResetsNextDay() {
        var limiter = new ChatRateLimiter(new ChatProperties(500, 100, 2), clock);

        assertThat(limiter.tryAcquire("a")).isEqualTo(Decision.ALLOWED);
        assertThat(limiter.tryAcquire("b")).isEqualTo(Decision.ALLOWED);
        assertThat(limiter.tryAcquire("c")).isEqualTo(Decision.DAILY_LIMIT_REACHED);

        clock.advance(Duration.ofDays(1));
        assertThat(limiter.tryAcquire("c")).isEqualTo(Decision.ALLOWED);
    }
}
