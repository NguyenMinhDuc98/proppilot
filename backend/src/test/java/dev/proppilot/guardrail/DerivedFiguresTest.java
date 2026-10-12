package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The figures that follow from the rows of a tool result, and the bounds on how many are read. */
class DerivedFiguresTest {

    private static Set<String> derivedFrom(String... results) {
        var figures = new HashSet<String>();
        var derived = new DerivedFigures(figures::add);
        for (var result : results) {
            ToolResultReader.read(result, (kind, written, normalised, separately, at) -> { }, length -> { }, derived);
        }
        return figures;
    }

    /** {@code count} objects, each made by {@code row} from its index. */
    private static String rows(int count, IntFunction<String> row) {
        return IntStream.range(0, count).mapToObj(row).collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void theRowsThatShareAValueAreCountedForEachGroupOfTwoOrMore() {
        var result = "{\"tenants\":" + rows(15, i -> "{\"unit\":\"U" + i + "\",\"daysOverdue\":" + (i < 11 ? 103 : 41)
                + ",\"unpaidMonths\":" + (i < 11 ? 3 : 1) + "}") + "}";

        var figures = derivedFrom(result);

        assertThat(figures).contains("11", "4");
        assertThat(figures).doesNotContain("15", "12");
    }

    @Test
    void aValueOfOneRowIsNoGroup() {
        var result = rows(14, i -> "{\"unit\":\"U" + i + "\",\"floor\":" + i + "}");

        assertThat(derivedFrom(result)).doesNotContain("1");
    }

    @Test
    void textsBooleansAndNullsAreValuesRowsShare() {
        var result = rows(30, i -> "{\"status\":\"" + (i < 12 ? "UNPAID" : "PAID") + "\",\"late\":" + (i < 13)
                + ",\"paidOn\":" + (i < 14 ? "null" : "\"2026-01-0" + (i % 9 + 1) + "\"") + "}");

        assertThat(derivedFrom(result)).contains("12", "18", "13", "17", "14");
    }

    @Test
    void aValueIsTheSameWhateverTheFormOfItsNumber() {
        var result = "[{\"a\":4.8E+3},{\"a\":4800},{\"a\":4800.00},{\"a\":4.8E+3},{\"a\":4.8E+3},{\"a\":5}]";

        assertThat(derivedFrom(result)).contains("5");
    }

    @Test
    void anAmountIsSummedOverAllRowsAndOverEachGroupOfRowsThatShareAValueOfAnotherField() {
        var result = "{\"payments\":[{\"amount\":4.8E+3,\"status\":\"UNPAID\"},{\"amount\":4.8E+3,\"status\":\"UNPAID\"},"
                + "{\"amount\":4.8E+3,\"status\":\"UNPAID\"},{\"amount\":4.8E+3,\"status\":\"UNPAID\"},"
                + "{\"amount\":4.8E+3,\"status\":\"PAID\"},{\"amount\":5000,\"status\":\"PAID\"},"
                + "{\"amount\":5100,\"status\":\"LATE\"}]}";

        var figures = derivedFrom(result);

        assertThat(figures).contains("19200", "9800", "34100");
        // 4,800 and 5,100 are rows that nothing groups
        assertThat(figures).doesNotContain("9900", "10100", "14900", "24300");
    }

    @Test
    void theSumsAreExact() {
        var result = "[{\"fee\":0.1,\"kind\":\"a\"},{\"fee\":0.2,\"kind\":\"a\"},{\"fee\":0.7,\"kind\":\"b\"}]";

        assertThat(derivedFrom(result)).contains("1", "0.3");
    }

    @Test
    void aSumHasNoSignAsAFigureOfAnAnswerHasNone() {
        var result = "[{\"balance\":-4800,\"k\":1},{\"balance\":-1200,\"k\":1}]";

        assertThat(derivedFrom(result)).contains("6000");
    }

    @Test
    void anAmountIsNotSummedOverTheRowsThatShareItsOwnValue() {
        var result = "[{\"rent\":100},{\"rent\":100},{\"rent\":300}]";

        var figures = derivedFrom(result);

        assertThat(figures).contains("500", "2");
        assertThat(figures).doesNotContain("200");
    }

    @Test
    void aFieldWithATextAmongItsValuesIsNoAmount() {
        var result = "[{\"code\":101,\"k\":1},{\"code\":\"102\",\"k\":1},{\"code\":103,\"k\":1}]";

        assertThat(derivedFrom(result)).doesNotContain("204", "306");
    }

    @Test
    void aFieldThatIsNullInSomeRowsIsSummedOverTheOthers() {
        var result = "[{\"paid\":100,\"k\":1},{\"paid\":null,\"k\":1},{\"paid\":250,\"k\":1}]";

        assertThat(derivedFrom(result)).contains("350");
    }

    @Test
    void anArrayOfOneRowAndAnArrayOfNumbersDeriveNothing() {
        assertThat(derivedFrom("{\"units\":[{\"rent\":4800}]}")).isEmpty();
        assertThat(derivedFrom("{\"units\":[1,1,1,4800,4800]}")).isEmpty();
    }

    @Test
    void aNumberBetweenTheObjectsOfAnArrayIsNoRow() {
        assertThat(derivedFrom("{\"rows\":[{\"rent\":4800},3,{\"rent\":4800}]}")).contains("9600");
    }

    @Test
    void theRowsOfTwoArraysAreNeverAddedOrCountedTogether() {
        var first = rows(6, i -> "{\"amount\":1000,\"k\":\"x\"}");
        var second = rows(6, i -> "{\"amount\":1000,\"k\":\"x\"}");

        var figures = derivedFrom("{\"a\":" + first + ",\"b\":" + second + "}", second);

        assertThat(figures).contains("6", "6000");
        assertThat(figures).doesNotContain("12", "12000");
    }

    @Test
    void anArrayInsideARowIsATableOfItsOwn() {
        var result = "{\"leases\":[{\"id\":1,\"payments\":" + rows(5, i -> "{\"amount\":700,\"s\":\"U\"}") + "},"
                + "{\"id\":2,\"payments\":" + rows(3, i -> "{\"amount\":900,\"s\":\"U\"}") + "}]}";

        var figures = derivedFrom(result);

        assertThat(figures).contains("3500", "2700", "5", "3");
        assertThat(figures).doesNotContain("6200");
    }

    @ParameterizedTest
    @CsvSource({"0.5,50", "0.857,86", "0.857,85.7", "0.8571,85.71", "0.05,5", "0.125,12.5", "0.125,13", "0.999,100",
            "0.30,30", "5E-1,50"})
    void aFractionIsAlsoAPercentageRoundedAsAnAnswerPrintsIt(String number, String percentage) {
        assertThat(derivedFrom("{\"rate\":" + number + "}")).contains(percentage);
    }

    @ParameterizedTest
    @CsvSource({"1", "1.0", "0", "0.0", "-0.5", "1.5", "50", "85.5", "2"})
    void aNumberThatIsNoFractionOfAWholeIsNoRate(String number) {
        assertThat(derivedFrom("{\"rate\":" + number + "}")).isEmpty();
    }

    @Test
    void aFractionInATextIsNotARate() {
        assertThat(derivedFrom("{\"rate\":\"0.5\",\"note\":\"fee 0.25 a day\"}")).isEmpty();
    }

    @Test
    void anArrayOfFiveHundredRowsIsReadAndOneOfMoreRowsDerivesNothing() {
        var read = rows(500, i -> "{\"v\":2,\"k\":\"x\"}");
        var tooLong = rows(501, i -> "{\"v\":2,\"k\":\"x\"}");

        assertThat(derivedFrom(read)).contains("500", "1000");
        // 500 of its 501 rows would give a count and a sum that are no figures of the array
        assertThat(derivedFrom(tooLong)).isEmpty();
    }

    @Test
    void anArrayOfMoreThanFiveHundredRowsDoesNotStopItsNeighboursFromBeingRead() {
        var result = "{\"all\":" + rows(600, i -> "{\"v\":1}") + ",\"few\":" + rows(3, i -> "{\"v\":7}") + "}";

        assertThat(derivedFrom(result)).containsExactlyInAnyOrder("3", "21");
    }

    @Test
    void aRowIsReadForItsFirstTwentyFields() {
        var fields = IntStream.range(0, 25).mapToObj(f -> "\"f" + f + "\":" + (1000 + f))
                .collect(Collectors.joining(",", "{", "}"));

        var figures = derivedFrom("[" + fields + "," + fields + "]");

        assertThat(figures).contains("2038");
        assertThat(figures).doesNotContain("2040", "2048");
    }

    @Test
    void aTableIsReadForItsFirstTwentyFieldsWhateverTheRowsCallThem() {
        var result = rows(3, i -> IntStream.range(0, 12).mapToObj(f -> "\"r" + i + "f" + f + "\":" + (1000 + 12 * i + f))
                .collect(Collectors.joining(",", "{", "}")));

        var figures = derivedFrom(result);

        // the twelve fields of the first row and the first eight of the second
        assertThat(figures).contains("1019");
        assertThat(figures).doesNotContain("1020", "1024");
    }

    @Test
    void aFieldIsGroupedByItsFirstThirtyValues() {
        var result = rows(80, i -> "{\"k\":\"v" + i % 40 + "\",\"amount\":" + (i < 40 ? 1000 + i : 5000 + i - 40) + "}");

        var figures = derivedFrom(result);

        assertThat(figures).contains(Integer.toString(6000 + 2 * 29));
        assertThat(figures).doesNotContain(Integer.toString(6000 + 2 * 30));
    }

    @Test
    void aRunDerivesAtMostTwentyThousandFigures() {
        var result = "[" + "[{\"a\":1},{\"a\":2}],".repeat(30_000) + "[{\"a\":1},{\"a\":2}]]";
        var count = new int[1];
        var derived = new DerivedFigures(figure -> count[0]++);

        ToolResultReader.read(result, (kind, written, normalised, separately, at) -> { }, length -> { }, derived);

        assertThat(count[0]).isEqualTo(DerivedFigures.MAX_FIGURES);
        assertThat(derived.spent()).isTrue();
    }

    @Test
    void anArrayNestedDeeperThanSixLevelsIsNotRead() {
        var table = "[{\"x\":1100},{\"x\":2200}]";
        var read = "{\"a\":{\"b\":{\"c\":{\"d\":" + table + "}}}}";
        var ignored = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":" + table + "}}}}}";

        assertThat(derivedFrom(read)).contains("3300");
        assertThat(derivedFrom(ignored)).isEmpty();
    }

    @Test
    void aResultThatIsNotJsonDerivesNothing() {
        assertThat(derivedFrom("No units found (0).", "[{\"a\":1},{\"a\":", "")).isEmpty();
    }
}
