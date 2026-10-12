package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.tools.ToolRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The prefix every request starts with (the tool definitions and the system prompt) is what prompt caching can save,
 * and the API caches a prefix only above a minimum that depends on the model: 4,096 tokens for Haiku 4.5. Below it the
 * request is accepted and nothing is cached, with no error, so a prompt trimmed too far would turn caching off without
 * anything failing. This test is what fails instead.
 */
class PromptPrefixIT extends PostgresIntegrationTest {

    /** The minimum of the default model, claude-haiku-4-5. */
    private static final int CACHE_MINIMUM_TOKENS = 4_096;

    /**
     * What the prefix has to be at the ratio below: a little over the minimum, so that a prefix a few tokens denser
     * than this estimate still crosses it. The margin is small on purpose: the prefix is only 4,252 tokens.
     */
    private static final int REQUIRED_ESTIMATED_TOKENS = 4_200;

    /**
     * Characters per token of the prefix as the API counted it: on 2026-10-12 the prefix of 15,311 characters (the 11
     * tool definitions as sent and the system prompt) was written to the cache by the first call of a run as 4,252
     * tokens, which is 3.60 characters per token. It is a ratio of this mix of JSON and prose, not a tokenizer. The
     * estimate made before that measurement, 3.22 characters per token from the 3,660-token prefix of the first live
     * run, was too low a ratio for what was added (prose is less dense than JSON) and put the prefix at 4,760 tokens,
     * 12 percent more than it was.
     */
    private static final double CHARS_PER_TOKEN = 15_311 / 4_252.0;

    @Autowired
    ToolRegistry registry;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    /** The tool definitions as the Messages API request carries them. */
    private String toolDefinitions() {
        var tools = json.createArrayNode();
        for (var spec : registry.specs()) {
            tools.addObject().put("name", spec.name()).put("description", spec.description())
                    .set("input_schema", spec.inputSchema());
        }
        return tools.toString();
    }

    @Test
    void theToolDefinitionsAndTheSystemPromptAreWellOverTheMinimumTheSmallModelCaches() {
        var prefix = toolDefinitions() + SystemPrompt.forDate(LocalDate.of(2026, 10, 12));

        double estimatedTokens = prefix.length() / CHARS_PER_TOKEN;

        assertThat(estimatedTokens).as("estimated tokens of %d characters at %.2f characters per token",
                prefix.length(), CHARS_PER_TOKEN).isGreaterThanOrEqualTo(REQUIRED_ESTIMATED_TOKENS);
        assertThat(REQUIRED_ESTIMATED_TOKENS).isGreaterThan(CACHE_MINIMUM_TOKENS);
    }

    @Test
    void thePrefixDoesNotDependOnTheQuestionOnlyOnTheDate() {
        var today = SystemPrompt.forDate(LocalDate.of(2026, 10, 12));
        var tomorrow = SystemPrompt.forDate(LocalDate.of(2026, 10, 13));

        assertThat(tomorrow).hasSameSizeAs(today);
        assertThat(tomorrow.replace("2026-10-13", "2026-10-12")).isEqualTo(today);
    }

    @Test
    void theBuildingsAndCitiesTheConventionsNameAreTheSeededOnes() {
        var prompt = SystemPrompt.forDate(LocalDate.of(2026, 10, 12));
        var byCity = new TreeMap<String, List<String>>();
        jdbc.queryForList("""
                select b.code, b.name, c.name_en as city
                from buildings b join cities c on c.id = b.city_id order by b.code""").forEach(row ->
                byCity.computeIfAbsent((String) row.get("city"), city -> new ArrayList<>())
                        .add(row.get("code") + " " + row.get("name")));

        assertThat(byCity).hasSize(3);
        byCity.forEach((city, buildings) -> assertThat(prompt)
                .contains(String.join(" and ", buildings) + " in " + city));
    }

    @Test
    void everyBuildingHasTwentyFiveUnitsCodedByLetterFloorAndPlace() {
        var perBuilding = jdbc.queryForList("select count(*) as units from units group by building_id", Long.class);
        var misshapen = jdbc.queryForObject("select count(*) from units where code !~ '^[A-F]-[1-5]0[1-5]$'",
                Long.class);

        assertThat(perBuilding).hasSize(6).containsOnly(25L);
        assertThat(misshapen).isZero();
    }

    @Test
    void everyPaymentStatusTheToolReturnsIsDefinedInTheConventions() throws Exception {
        var prompt = SystemPrompt.forDate(LocalDate.of(2026, 10, 12));
        var statuses = new java.util.TreeSet<String>();
        for (var unit : jdbc.queryForList("select code from units where status = 'OCCUPIED' order by code",
                String.class)) {
            var result = registry.execute("get_payment_history", json.readTree("{\"unit_code\":\"" + unit + "\"}"));
            json.readTree(result.content()).get("payments").forEach(row -> statuses.add(row.get("status").asText()));
        }

        assertThat(statuses).isNotEmpty().allSatisfy(status -> assertThat(prompt).contains(status));
    }
}
