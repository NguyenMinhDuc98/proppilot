package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.agent.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();

    private RetryPolicy policy(int maxRetries, Duration baseDelay, DoubleSupplier random) {
        return new RetryPolicy(maxRetries, baseDelay, sleeps::add, random);
    }

    private static LlmException transientFailure() {
        return LlmException.retryable("boom", ErrorCode.LLM_OVERLOADED);
    }

    private String alwaysFail(RetryPolicy policy, LlmException failure) {
        return policy.execute(text -> { }, sink -> {
            attempts.incrementAndGet();
            throw failure;
        });
    }

    @Test
    void waitsTwiceAsLongBeforeEachRetryWithJitterInsideTheCeiling() {
        var policy = policy(3, ONE_SECOND, () -> 0.5);

        assertThatThrownBy(() -> alwaysFail(policy, transientFailure())).isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void jitterRangesFromNoWaitToTheFullCeiling() {
        var noWait = policy(1, ONE_SECOND, () -> 0.0);
        var fullWait = policy(1, ONE_SECOND, () -> 1.0);

        assertThatThrownBy(() -> alwaysFail(noWait, transientFailure())).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> alwaysFail(fullWait, transientFailure())).isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(Duration.ZERO, ONE_SECOND);
    }

    @Test
    void backoffCeilingIsCappedAtTwentySeconds() {
        var policy = policy(3, Duration.ofSeconds(10), () -> 1.0);

        assertThatThrownBy(() -> alwaysFail(policy, transientFailure())).isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(20));
    }

    @Test
    void waitsForTheTimeTheProviderAskedForInsteadOfBackingOff() {
        var policy = policy(1, ONE_SECOND, () -> {
            throw new AssertionError("jitter must not be used when Retry-After is given");
        });
        var failure = LlmException.retryable("slow down", ErrorCode.LLM_OVERLOADED, Duration.ofSeconds(7), null);

        assertThatThrownBy(() -> alwaysFail(policy, failure)).isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void retryAfterIsCappedAtThirtySeconds() {
        var policy = policy(1, ONE_SECOND, () -> 0.5);
        var failure = LlmException.retryable("slow down", ErrorCode.LLM_OVERLOADED, Duration.ofMinutes(10), null);

        assertThatThrownBy(() -> alwaysFail(policy, failure)).isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void makesMaxRetriesPlusOneAttemptsThenRethrowsTheLastFailure() {
        var policy = policy(3, ONE_SECOND, () -> 0.5);
        var last = LlmException.retryable("the last one", ErrorCode.LLM_OVERLOADED);

        assertThatThrownBy(() -> policy.execute(text -> { }, sink -> {
            if (attempts.incrementAndGet() == 4) {
                throw last;
            }
            throw transientFailure();
        })).isSameAs(last);

        assertThat(attempts).hasValue(4);
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void zeroRetriesMeansASingleAttempt() {
        var policy = policy(0, ONE_SECOND, () -> 0.5);

        assertThatThrownBy(() -> alwaysFail(policy, transientFailure())).isInstanceOf(LlmException.class);

        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void doesNotRetryPermanentFailures() {
        var policy = policy(3, ONE_SECOND, () -> 0.5);

        assertThatThrownBy(() -> alwaysFail(policy, new LlmException("bad key", ErrorCode.LLM_AUTH)))
                .isInstanceOf(LlmException.class);

        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void returnsTheResultOfTheFirstAttemptThatSucceeds() {
        var policy = policy(3, ONE_SECOND, () -> 0.5);

        var result = policy.execute(text -> { }, sink -> {
            if (attempts.incrementAndGet() < 3) {
                throw transientFailure();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void neverRetriesOnceTextHasReachedTheCaller() {
        var policy = policy(3, ONE_SECOND, () -> 0.5);
        var delivered = new ArrayList<String>();

        assertThatThrownBy(() -> policy.execute(delivered::add, sink -> {
            attempts.incrementAndGet();
            sink.accept("partial answer");
            throw transientFailure();
        })).isInstanceOf(LlmException.class);

        assertThat(attempts).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(delivered).containsExactly("partial answer");
    }

    @Test
    void retriesAFailureThatHappenedBeforeAnyTextAndDeliversTheTextOnce() {
        var policy = policy(3, ONE_SECOND, () -> 0.5);
        var delivered = new ArrayList<String>();

        var result = policy.execute(delivered::add, sink -> {
            if (attempts.incrementAndGet() == 1) {
                throw transientFailure();
            }
            sink.accept("hello");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(delivered).containsExactly("hello");
    }

    @Test
    void anInterruptedWaitStopsRetryingAndKeepsTheInterruptFlag() {
        var policy = new RetryPolicy(3, ONE_SECOND, wait -> {
            throw new InterruptedException();
        }, () -> 0.5);

        try {
            assertThatThrownBy(() -> alwaysFail(policy, transientFailure()))
                    .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isFalse());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(attempts).hasValue(1);
    }
}
