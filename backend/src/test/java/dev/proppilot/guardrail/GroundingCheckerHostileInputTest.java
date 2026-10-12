package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The answer, the tool results and the question that the guardrail reads are whatever a model or a visitor made them,
 * and java.util.regex recurses once for each round of a repeated group. These read hostile texts of a million
 * characters on a thread with a deliberately small 256 KB stack, a quarter of what a test thread has and stricter than
 * the default stack of the production JVM: a recursion that needs more fails here whatever the size of the text, and a
 * scan that is not linear fails by the clock.
 */
class GroundingCheckerHostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final long SMALL_STACK_BYTES = 256 * 1024;
    private static final int MILLION = 1_000_000;
    /** What deriving figures from a result of a million characters may take, with the time a JVM needs to warm up. */
    private static final Duration DERIVATION_LIMIT = Duration.ofSeconds(1);
    private static final String DERIVATION_ANSWER =
            "Ahmed Al-Harbi owes 4,950 SAR for A-203, 11 tenants are late and 500 units.";

    private static final TenantNames.Name AHMED = new TenantNames.Name("Ahmed Al-Harbi", "أحمد الحربي");
    private final GroundingChecker checker = new GroundingChecker(new TenantNames(() -> List.of(AHMED)));

    private static String hostile(String unit) {
        return unit.repeat(MILLION / unit.length());
    }

    private static Evidence given(List<String> toolResults, List<String> context) {
        return new Evidence(toolResults, context, Set.of("LSE-A-203#5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[LSE-", "[LSE-A-203#5] ", "[LSE-A-203#5,", "A-", "A-203 ", "A-203", "1,", "1,234,", "1.",
            "٤", "٬٤", "9", "(((", "Ahmed Al-Harbi ", "أحمد الحربي ", "Ahmed ", "\u0000", "😀", "\n1. ", "> ", " ",
            "[", "]", "(a|b)", "ـ", "LSE-", "101,102,", "100,", "100.200.", "1.2.", ".9", "٫٩"})
    void aHostileAnswerIsCheckedInBoundedTime(String unit) {
        var answer = hostile(unit);

        var violations = onSmallStack(() -> checker.check(answer, given(List.of("{\"units\":[1,2,3]}"), List.of("q"))));

        assertThat(violations).hasSizeLessThanOrEqualTo(GroundingChecker.MAX_VIOLATIONS);
        violations.forEach(v -> assertThat(v.value().length()).isLessThanOrEqualTo(Violation.MAX_LENGTH));
    }

    @Test
    void aChainOfAMillionCharactersOfGroupsAndOneOfPointsAreEachReadInBoundedTime() {
        var groups = "100" + ",100".repeat(MILLION / 4);
        var points = "100" + ".100".repeat(MILLION / 4);

        var ofGroups = onSmallStack(() -> checker.check(groups, given(List.of(), List.of("q"))));
        var ofPoints = onSmallStack(() -> checker.check(points, given(List.of(), List.of("q"))));

        assertThat(ofGroups).hasSize(1);
        assertThat(ofPoints).hasSize(1);
        assertThat(ofPoints.get(0).value()).isEqualTo("100");
    }

    @Test
    void aSingleRunOfAMillionDigitsIsOneFigure() {
        var violations = onSmallStack(() -> checker.check("9".repeat(MILLION), given(List.of(), List.of("q"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).kind()).isEqualTo(ClaimKind.NUMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "{\"a\":", "[[],", "[1,", "\"", "9", "A-203 ", "{\"a\":[", "[\"A-203\",", "\\u0041",
            "1,234,", "Ahmed Al-Harbi ", "]", "}", ",", "\"101,102,", "\"1.2."})
    void hostileToolResultsOfAMillionCharactersAreReadInBoundedTime(String unit) {
        var result = hostile(unit);
        var answer = "Ahmed Al-Harbi owes 4,950 SAR for A-203 [LSE-A-203#5] and 12 units.";

        var violations = onSmallStack(() -> checker.check(answer, given(List.of(result), List.of("q"))));

        assertThat(violations).hasSizeLessThanOrEqualTo(GroundingChecker.MAX_VIOLATIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "A-203 ", "Ahmed Al-Harbi ", "1,234,", "[LSE-A-203#5] ", "٤٬٩٥٠ ", "\n"})
    void hostileQuestionsAndHistoriesOfAMillionCharactersAreReadInBoundedTime(String unit) {
        var text = hostile(unit);
        var answer = "Ahmed Al-Harbi owes 4,950 SAR for A-203 [LSE-A-203#5].";

        var violations = onSmallStack(() -> checker.check(answer, given(List.of(), List.of(text, text))));

        assertThat(violations).hasSizeLessThanOrEqualTo(GroundingChecker.MAX_VIOLATIONS);
    }

    @Test
    void aMillionLevelsOfNestingInAToolResultCostsNoStackAndItsFiguresAreStillRead() {
        var nested = "[".repeat(MILLION) + "4950";
        var answer = "The amount is 4,950 SAR.";

        var violations = onSmallStack(() -> checker.check(answer, given(List.of(nested), List.of("q"))));

        assertThat(violations).isEmpty();
    }

    private static String repeated(String head, String unit, String tail, int characters) {
        return head + unit.repeat(Math.max(1, characters / unit.length())) + tail;
    }

    /** An object of {@code count} fields, named and valued by {@code field}. */
    private static String object(int count, IntFunction<String> field) {
        return IntStream.range(0, count).mapToObj(field).collect(Collectors.joining(",", "{", "}"));
    }

    /** 500 rows of 20 numeric fields of 29 values each: the most that is read, and some 11,600 figures to derive. */
    private static String fullTable() {
        return IntStream.range(0, RowTable.MAX_ROWS)
                .mapToObj(row -> object(RowTable.MAX_FIELDS, f -> "\"f" + f + "\":" + (row % 29 + f)))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String hostileResult(String shape) {
        return switch (shape) {
            // a hundred thousand rows, far more than an array may have to be derived from
            case "rows" -> repeated("{\"tenants\":[", "{\"a\":1},", "{\"a\":1}]}", MILLION);
            case "wide rows" -> repeated("[", "{\"unit\":\"A-203\",\"d\":103,\"amount\":4800.50,\"s\":\"UNPAID\"},", "{}]",
                    MILLION);
            // a million characters of arrays of two rows, each of which derives a figure
            case "tables" -> repeated("[", "[{\"a\":1},{\"a\":2}],", "[]]", MILLION);
            case "fractions" -> repeated("[", "0.5,", "0.5]", MILLION);
            // tables of the largest size that is read, each of which has more groups than the run derives figures for
            case "full tables" -> "[" + (fullTable() + ",").repeat(12) + "[]]";
            // objects whose fields are all named differently, and rows whose values are all different
            case "unique fields" -> "[" + (object(20_000, f -> "\"f" + f + "\":" + f % 7) + ",").repeat(5) + "{}]";
            case "unique values" -> IntStream.range(0, 500)
                    .mapToObj(row -> object(20, f -> "\"f" + f + "\":" + (row * 20 + f)))
                    .collect(Collectors.joining(",", "[", "]"));
            // 900 levels of arrays of rows, as deep as Jackson reads, of which the first rows are read
            case "deep" -> "{\"a\":[".repeat(450) + "1" + "]}".repeat(450);
            default -> throw new IllegalArgumentException(shape);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"rows", "wide rows", "tables", "fractions", "full tables", "unique fields", "unique values",
            "deep"})
    void aHostileResultIsDerivedFromInBoundedTimeOnASmallStack(String shape) {
        var result = hostileResult(shape);

        var started = System.nanoTime();
        var violations = onSmallStack(() -> checker.check(DERIVATION_ANSWER, given(List.of(result), List.of("q"))));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(violations).hasSizeLessThanOrEqualTo(GroundingChecker.MAX_VIOLATIONS);
        assertThat(elapsed).as("the time to check against %d characters of %s", result.length(), shape)
                .isLessThan(DERIVATION_LIMIT);
    }

    private <T> T onSmallStack(Callable<T> work) {
        var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(null, task, "small-stack", SMALL_STACK_BYTES));
        try {
            return executor.submit(work).get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("The text could not be read on a " + SMALL_STACK_BYTES / 1024 + " KB stack",
                    e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Reading the text took more than " + LIMIT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
