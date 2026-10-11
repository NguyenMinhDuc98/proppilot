package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
