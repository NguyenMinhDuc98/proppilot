package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a result states about the data, which is what an answer is held against. */
class ToolResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anOkResultStatesItsContent() {
        var result = ToolResult.ok(JSON, Map.of("monthlyRent", 4950), "found");

        assertThat(result.evidence()).isEqualTo(result.content()).isEqualTo("{\"monthlyRent\":4950}");
    }

    record Invoice(String month, BigDecimal amount, BigDecimal remainingAmount) {
    }

    record Occupancy(BigDecimal occupancyRatePercent, BigDecimal rentCollected, List<Invoice> payments) {
    }

    @Test
    void anAmountReachesTheModelAsAPlainDecimalNotAnExponent() {
        var data = new Occupancy(new BigDecimal("80.0"), new BigDecimal("93100.00"),
                List.of(new Invoice("2026-10", new BigDecimal("4800.00"), new BigDecimal("4800.00"))));

        var result = ToolResult.ok(JSON, data, "occupancy");

        assertThat(result.content()).isEqualTo("{\"occupancyRatePercent\":80.0,\"rentCollected\":93100.00,"
                + "\"payments\":[{\"month\":\"2026-10\",\"amount\":4800.00,\"remainingAmount\":4800.00}]}");
        assertThat(result.evidence()).isEqualTo(result.content());
    }

    @Test
    void wholeAmountsAreNeverWrittenInScientificNotation() {
        var result = ToolResult.ok(JSON, Map.of("eighty", new BigDecimal("80"), "thousand", new BigDecimal("1E+3"),
                "debt", new BigDecimal("-4800.00"), "half", new BigDecimal("0.50")), "amounts");

        assertThat(result.content()).doesNotContainPattern("[0-9][eE][+-]?[0-9]")
                .contains("\"eighty\":80", "\"thousand\":1000", "\"debt\":-4800.00", "\"half\":0.50");
    }

    @Test
    void anErrorStatesItsText() {
        var result = ToolResult.error("3 tenants match 'Ahmed': Ahmed Al-Harbi (unit B-203)");

        assertThat(result.evidence()).isEqualTo(result.content());
    }

    @Test
    void aRejectionStatesNothingAndIsStillAnErrorTheModelAndTheClientRead() {
        var result = ToolResult.rejected("No unit with code 'Z-999'. Codes look like A-203");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo(result.summary()).contains("Z-999", "A-203");
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void whatAToolSaysItStatesCanBeSetApartFromWhatTheModelReads() {
        var result = ToolResult.ok(JSON, Map.of("query", "late fee 150", "results", 0), "none")
                .withEvidence("{\"results\":0}").withUi(JSON.createObjectNode());

        assertThat(result.content()).contains("late fee 150");
        assertThat(result.evidence()).isEqualTo("{\"results\":0}");
        assertThat(result.ui()).isNotNull();
    }
}
