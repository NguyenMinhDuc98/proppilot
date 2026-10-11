package dev.proppilot.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every question that reaches the model goes through the assessor, and java.util.regex recurses once for each round of
 * a repeated group. These run it on hostile questions of up to a million characters, on a thread with a 256 KB stack
 * (the production JVM runs with -Xss256k), a quarter of what a test thread has: a recursion that needs more than that
 * fails here whatever the size of the question, and a pattern that backtracks fails by the clock.
 */
class ComplexityAssessorHostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);
    private static final long SMALL_STACK_BYTES = 256 * 1024;

    private final ComplexityAssessor assessor = new ComplexityAssessor();

    @ParameterizedTest
    @ValueSource(strings = {"and then ", ", , , ", "a ", "و", "A-203 ", "(((", "ثم ", "late payment fee of ",
            "record a payment of ", "ـ", "9", "\u0000", "😀", "within 9 days ", "خلال 9 ايام ", "غرامة التأخير في ",
            "late fee ", "overdue rent late fee "})
    void aHostileQuestionOfAHundredThousandCharactersIsReadInBoundedTime(String unit) {
        var hostile = unit.repeat(100_000 / unit.length());

        var complexity = onSmallStack(hostile);

        assertThat(complexity.rules()).contains(ComplexityRule.LONG_QUESTION);
    }

    @Test
    void aSingleUnbrokenWordOfAMillionCharactersIsReadInBoundedTime() {
        assertThat(onSmallStack("ا".repeat(1_000_000)).rules()).containsExactly(ComplexityRule.LONG_QUESTION);
        assertThat(onSmallStack("a".repeat(1_000_000)).rules()).containsExactly(ComplexityRule.LONG_QUESTION);
    }

    @Test
    void aCommandThatNeverEndsIsReadInBoundedTime() {
        var command = "log a repair for " + "unit and ".repeat(50_000);

        assertThat(onSmallStack(command).rules()).contains(ComplexityRule.LONG_QUESTION);
    }

    private Complexity onSmallStack(String question) {
        var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(null, task, "small-stack", SMALL_STACK_BYTES));
        try {
            return executor.submit(() -> assessor.assess(question, 0)).get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("The question could not be scored on a " + SMALL_STACK_BYTES / 1024 + " KB stack",
                    e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Scoring the question took more than " + LIMIT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
