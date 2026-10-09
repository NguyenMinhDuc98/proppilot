package dev.proppilot.agent.llm;

import dev.proppilot.agent.ErrorCode;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a model call that failed for a transient reason: exponential backoff with full jitter, or the wait the
 * provider asked for. Never retries once text has reached the caller, because the user has already seen it, and never
 * waits longer than the time left, so the original failure is reported instead of a timeout after the wait.
 */
final class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);
    private static final double BACKOFF_FACTOR = 2;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    /** Seam so tests never really wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One try of the call. Text it produces must go through the given consumer. */
    @FunctionalInterface
    interface Attempt<T> {
        T run(Consumer<String> onTextDelta);
    }

    private final int maxRetries;
    private final Duration baseDelay;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    RetryPolicy(int maxRetries, Duration baseDelay, Sleeper sleeper, DoubleSupplier random) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.sleeper = sleeper;
        this.random = random;
    }

    static RetryPolicy withBackoff(int maxRetries, Duration baseDelay) {
        return new RetryPolicy(maxRetries, baseDelay, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Runs the attempt up to {@code maxRetries + 1} times and rethrows the last failure.
     *
     * @param timeLeft the time the whole call may still take; a retry whose wait is not shorter than this is skipped
     */
    <T> T execute(Supplier<Duration> timeLeft, Consumer<String> onTextDelta, Attempt<T> attempt) {
        var textDelivered = new AtomicBoolean();
        Consumer<String> tracking = text -> {
            textDelivered.set(true);
            onTextDelta.accept(text);
        };
        for (int retry = 0; ; retry++) {
            try {
                return attempt.run(tracking);
            } catch (LlmException e) {
                if (!e.retryable() || textDelivered.get() || retry >= maxRetries) {
                    throw e;
                }
                var delay = delayBeforeRetry(retry, e);
                if (delay.compareTo(timeLeft.get()) >= 0) {
                    log.warn("Claude call failed ({}); not retrying, a {} ms wait would use up the time left", e.code(), delay.toMillis());
                    throw e;
                }
                log.warn("Claude call failed ({}); retry {}/{} in {} ms", e.code(), retry + 1, maxRetries, delay.toMillis());
                pause(delay);
            }
        }
    }

    /** Full jitter: anywhere between zero and a ceiling that doubles with every retry. */
    private Duration delayBeforeRetry(int retry, LlmException failure) {
        var requested = failure.retryAfter();
        if (requested.isPresent()) {
            return requested.get().compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested.get();
        }
        double ceilingMs = Math.min(baseDelay.toMillis() * Math.pow(BACKOFF_FACTOR, retry), MAX_BACKOFF.toMillis());
        return Duration.ofMillis((long) (random.getAsDouble() * ceilingMs));
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting to retry", ErrorCode.LLM_ERROR, e);
        }
    }
}
