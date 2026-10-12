package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * An amount is written as the plain decimal it is. Jackson's tree strips the trailing zeros of a BigDecimal, so a tool
 * result that went through it carried 4800.00 as 4.8E+3 and 80 as 8E+1; these tests fail on that.
 */
class ToolJsonTest {

    /** Configured as the application's mapper is. */
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    record Row(String month, BigDecimal amount, BigDecimal remainingAmount, LocalDate paidOn) {
    }

    record Summary(BigDecimal occupancyRatePercent, BigDecimal rentCollected, List<Row> payments) {
    }

    private static String written(String amount) {
        return ToolJson.write(JSON, Map.of("amount", new BigDecimal(amount)));
    }

    @ParameterizedTest
    @CsvSource({
            "4800.00, 4800.00",
            "4800, 4800",
            "80, 80",
            "80.0, 80.0",
            "0.50, 0.50",
            "0.5, 0.5",
            "0, 0",
            "0.00, 0.00",
            "3799.50, 3799.50",
            "-4800.00, -4800.00",
            "-80, -80",
            "1E+3, 1000",
            "1E+10, 10000000000",
            "4.8E+3, 4800",
            "-1.25E+4, -12500",
            "1234567890123456.78, 1234567890123456.78",
            "123456789012345678901234567890.12, 123456789012345678901234567890.12",
            "0.0000001, 0.0000001"})
    void anAmountIsWrittenAsThePlainDecimalItIs(String amount, String plain) {
        assertThat(written(amount)).isEqualTo("{\"amount\":" + plain + "}");
    }

    @Test
    void aWholeAmountOfTwoDecimalsKeepsBothAndNeverBecomesAnExponent() {
        var json = ToolJson.write(JSON, new Row("2026-10", new BigDecimal("4800.00"), new BigDecimal("4800.00"), null));

        assertThat(json).isEqualTo("{\"month\":\"2026-10\",\"amount\":4800.00,\"remainingAmount\":4800.00,"
                + "\"paidOn\":null}");
        assertThat(json).doesNotContain("E+");
    }

    @Test
    void aRateComputedFromCountsIsWrittenWithItsOneDecimal() {
        var occupied = BigDecimal.valueOf(20 * 100).divide(BigDecimal.valueOf(25), 1, RoundingMode.HALF_UP);
        var half = BigDecimal.valueOf(1 * 100).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);

        assertThat(ToolJson.write(JSON, Map.of("occupied", occupied, "half", half)))
                .contains("\"occupied\":80.0").contains("\"half\":50.0").doesNotContain("E+");
    }

    @Test
    void aSumOfAmountsKeepsTheirScale() {
        var sum = new BigDecimal("4800.00").add(new BigDecimal("4800.00")).add(new BigDecimal("4800.00"));

        assertThat(written(sum.toString())).isEqualTo("{\"amount\":14400.00}");
    }

    @Test
    void nestedAmountsAndTheRestOfTheDataAreWrittenAsTheyWere() {
        var data = new Summary(new BigDecimal("80.0"), new BigDecimal("93100.00"), List.of(
                new Row("2026-10", new BigDecimal("4800.00"), new BigDecimal("4800.00"), null),
                new Row("2026-09", new BigDecimal("4800.00"), BigDecimal.ZERO, LocalDate.of(2026, 9, 3))));

        assertThat(ToolJson.write(JSON, data)).isEqualTo("{\"occupancyRatePercent\":80.0,\"rentCollected\":93100.00,"
                + "\"payments\":[{\"month\":\"2026-10\",\"amount\":4800.00,\"remainingAmount\":4800.00,"
                + "\"paidOn\":null},{\"month\":\"2026-09\",\"amount\":4800.00,\"remainingAmount\":0,"
                + "\"paidOn\":\"2026-09-03\"}]}");
    }

    @Test
    void theTreeKeepsTheScaleOfEveryAmountAndWritesThemPlain() {
        var tree = ToolJson.tree(JSON, Map.of("whole", new BigDecimal("4800.00"), "scaled", new BigDecimal("1E+3"),
                "fraction", new BigDecimal("0.50")));

        assertThat(tree.get("whole").decimalValue()).isEqualTo(new BigDecimal("4800.00"));
        assertThat(tree.get("whole").decimalValue().scale()).isEqualTo(2);
        assertThat(tree.get("fraction").decimalValue().scale()).isEqualTo(2);
        assertThat(tree.get("scaled").decimalValue()).isEqualByComparingTo("1000");
        assertThat(tree.toString()).contains("\"whole\":4800.00", "\"fraction\":0.50", "\"scaled\":1000")
                .doesNotContain("E+");
    }

    @Test
    void theTreeDoesNotPassAnAmountThroughAFloat() {
        var exact = new BigDecimal("12345678901234567890.12");

        var tree = ToolJson.tree(JSON, Map.of("amount", exact));

        assertThat(tree.get("amount").decimalValue()).isEqualTo(exact);
        assertThat(tree.toString()).isEqualTo("{\"amount\":12345678901234567890.12}");
    }

    @Test
    void theTreeOfAnObjectWithTextAndDatesIsTheObject() {
        var tree = ToolJson.tree(JSON, new Row("2026-09", new BigDecimal("4800.00"), BigDecimal.ZERO,
                LocalDate.of(2026, 9, 3)));

        assertThat(tree.get("month").asText()).isEqualTo("2026-09");
        assertThat(tree.get("paidOn").asText()).isEqualTo("2026-09-03");
        assertThat(tree.get("remainingAmount").decimalValue()).isEqualByComparingTo("0");
    }
}
