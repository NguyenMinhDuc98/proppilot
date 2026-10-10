package dev.proppilot.limit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-15T10:00:00Z"));

    @Test
    void refusesHitsOverTheLimitAndRecoversAsTheWindowSlides() {
        var limiter = new SlidingWindowLimiter(2, clock);

        assertThat(limiter.tryAcquire("a")).isTrue();
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        clock.advance(Duration.ofSeconds(31));
        assertThat(limiter.tryAcquire("a")).as("the first hit left the window, the second is still in it").isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void refusedHitsAreNotCounted() {
        var limiter = new SlidingWindowLimiter(1, clock);
        assertThat(limiter.tryAcquire("a")).isTrue();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(10));
            assertThat(limiter.tryAcquire("a")).isFalse();
        }

        clock.advance(Duration.ofSeconds(11));

        assertThat(limiter.tryAcquire("a")).as("only the accepted hit counts, and it is 61 seconds old").isTrue();
    }

    @Test
    void clientsAreLimitedIndependently() {
        var limiter = new SlidingWindowLimiter(1, clock);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("b")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }
}
