package dev.proppilot.stats;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.AgentResults;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.chat.ChatRun;
import dev.proppilot.chat.ChatRunRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class StatsApiIT extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    ChatRunRepository runs;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startWithNoRuns() {
        jdbc.update("delete from chat_runs");
    }

    private UUID save(String model, String cost, Usage usage, List<String> tools) {
        var id = UUID.randomUUID();
        var result = AgentResults.answered(model, usage, tools.size(), 2);
        runs.save(new ChatRun(id, NOW, "a question", "anthropic", result, tools, 1000, new BigDecimal(cost)));
        return id;
    }

    @Test
    void countsRunsAndCostByTheModelThatAnswered() throws Exception {
        save("claude-haiku-4-5-20251001", "0.002000", new Usage(100, 20), List.of());
        save("claude-haiku-4-5-20251001", "0.001000", new Usage(100, 20), List.of());
        save("claude-sonnet-4-5", "0.010000", new Usage(100, 20), List.of());

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byModel", hasSize(2)))
                .andExpect(jsonPath("$.byModel[0].model").value("claude-haiku-4-5-20251001"))
                .andExpect(jsonPath("$.byModel[0].runs").value(2))
                .andExpect(jsonPath("$.byModel[0].costUsd").value(0.003))
                .andExpect(jsonPath("$.byModel[1].model").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$.byModel[1].runs").value(1))
                .andExpect(jsonPath("$.byModel[1].costUsd").value(0.01));
    }

    @Test
    void sumsTheCacheTokensApartFromTheInputTokens() throws Exception {
        save("m", "0.001000", new Usage(100, 20, 3000, 400), List.of());
        save("m", "0.001000", new Usage(50, 10, 1000, 0), List.of());

        mvc.perform(get("/api/stats"))
                .andExpect(jsonPath("$.cache.readTokens").value(4000))
                .andExpect(jsonPath("$.cache.writeTokens").value(400))
                .andExpect(jsonPath("$.totalInputTokens").value(150))
                .andExpect(jsonPath("$.totalOutputTokens").value(30));
    }

    @Test
    void countsTheRunsTheGuardrailFlagged() throws Exception {
        save("m", "0.001000", new Usage(10, 2), List.of());
        var flagged = save("m", "0.001000", new Usage(10, 2), List.of());
        var flaggedTwice = save("m", "0.001000", new Usage(10, 2), List.of());
        jdbc.update("update chat_runs set guardrail_status = 'FLAGGED', guardrail_violations = 1 where id = ?", flagged);
        jdbc.update("update chat_runs set guardrail_status = 'FLAGGED', guardrail_violations = 3 where id = ?", flaggedTwice);

        mvc.perform(get("/api/stats")).andExpect(jsonPath("$.guardrail.flaggedRuns").value(2));
    }

    @Test
    void keepsTheFieldsItAlwaysHad() throws Exception {
        save("m", "0.002000", new Usage(100, 20), List.of("search_units", "get_unit_details"));

        mvc.perform(get("/api/stats"))
                .andExpect(jsonPath("$.totalRuns").value(1))
                .andExpect(jsonPath("$.totalCostUsd").value(0.002))
                .andExpect(jsonPath("$.avgCostUsd").value(0.002))
                .andExpect(jsonPath("$.avgLatencyMs").value(1000))
                .andExpect(jsonPath("$.avgToolCalls").value(2.0))
                .andExpect(jsonPath("$.toolUsage", hasSize(2)))
                .andExpect(jsonPath("$.toolUsage[0].tool").value("get_unit_details"))
                .andExpect(jsonPath("$.toolUsage[0].calls").value(1));
    }

    @Test
    void anEmptyHistoryHasZeroesAndNoModels() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(0))
                .andExpect(jsonPath("$.byModel", hasSize(0)))
                .andExpect(jsonPath("$.cache.readTokens").value(0))
                .andExpect(jsonPath("$.cache.writeTokens").value(0))
                .andExpect(jsonPath("$.guardrail.flaggedRuns").value(0));
    }
}
