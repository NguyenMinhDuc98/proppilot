package dev.proppilot.limit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DailyQuotaTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-15T10:00:00Z"));

    @Test
    void refusesOnceTheBudgetIsSpentAndStartsAgainTomorrow() {
        var quota = new DailyQuota(2, clock);

        assertThat(quota.tryTake()).isTrue();
        assertThat(quota.tryTake()).isTrue();
        assertThat(quota.tryTake()).isFalse();

        clock.advance(Duration.ofDays(1));
        assertThat(quota.tryTake()).isTrue();
    }

    @Test
    void aGivenBackUnitCanBeTakenAgain() {
        var quota = new DailyQuota(1, clock);
        assertThat(quota.tryTake()).isTrue();

        quota.giveBack();

        assertThat(quota.tryTake()).isTrue();
        assertThat(quota.tryTake()).isFalse();
    }

    @Test
    void givingBackMoreThanWasTakenNeverGrowsTheBudget() {
        var quota = new DailyQuota(1, clock);

        quota.giveBack();

        assertThat(quota.tryTake()).isTrue();
        assertThat(quota.tryTake()).isFalse();
    }
}
