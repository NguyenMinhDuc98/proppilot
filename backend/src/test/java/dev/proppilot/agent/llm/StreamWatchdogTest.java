package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StreamWatchdogTest {

    private final CountDownLatch closed = new CountDownLatch(1);

    @Test
    void closesTheStreamOnceTheLimitHasPassed() throws InterruptedException {
        try (var watchdog = new StreamWatchdog(closed::countDown, Duration.ofMillis(50))) {
            assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(watchdog.expired()).isTrue();
        }
    }

    @Test
    void leavesTheStreamAloneWhenTheWatchdogIsClosedInTime() throws InterruptedException {
        var watchdog = new StreamWatchdog(closed::countDown, Duration.ofMillis(300));
        watchdog.close();

        assertThat(closed.await(600, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(watchdog.expired()).isFalse();
    }
}
