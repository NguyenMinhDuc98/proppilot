package dev.proppilot.admin;

import static org.hamcrest.Matchers.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.SqlStatementCounter;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.AgentResults;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.ToolCallRecord;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ModelRoute;
import dev.proppilot.chat.ChatRun;
import dev.proppilot.chat.ChatRunRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(properties = "proppilot.admin.token=" + AdminRunsApiIT.TOKEN)
class AdminRunsApiIT extends PostgresIntegrationTest {

    static final String TOKEN = "integration-test-admin-token-1234";

    @Autowired
    MockMvc mvc;
    @Autowired
    ChatRunRepository runs;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminRunsController controller;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void startWithNoRuns() {
        jdbc.update("delete from chat_runs");
    }

    private void saveRun(String question, Instant at, List<String> tools) {
        var result = AgentResults.answered("claude-haiku-4-5-20251001", new Usage(120, 45), tools.size(), 2);
        runs.save(new ChatRun(UUID.randomUUID(), at, question, "anthropic", result, tools, 850,
                new BigDecimal("0.000345")));
    }

    private UUID saveRun(String question, Instant at, String model, AgentResult.Status status, int guardrailViolations) {
        var id = UUID.randomUUID();
        var result = AgentResults.ended(model, new Usage(120, 45), 0, 1, status);
        runs.save(new ChatRun(id, at, question, "anthropic", result, List.of(), 850, new BigDecimal("0.000345")));
        if (guardrailViolations > 0) {
            jdbc.update("update chat_runs set guardrail_status = 'FLAGGED', guardrail_violations = ? where id = ?",
                    guardrailViolations, id);
        }
        return id;
    }

    @Test
    void listsTheLatestRunsNewestFirstWithEverythingAnAdminNeeds() throws Exception {
        saveRun("older question", Instant.parse("2026-10-01T08:00:00Z"), List.of());
        saveRun("newest question", Instant.parse("2026-10-02T08:00:00Z"), List.of("search_units", "get_unit_details"));

        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].question").value("newest question"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].status").value("OK"))
                .andExpect(jsonPath("$[0].provider").value("anthropic"))
                .andExpect(jsonPath("$[0].model").value("claude-haiku-4-5-20251001"))
                .andExpect(jsonPath("$[0].toolNames").value(contains("search_units", "get_unit_details")))
                .andExpect(jsonPath("$[0].inputTokens").value(120))
                .andExpect(jsonPath("$[0].outputTokens").value(45))
                .andExpect(jsonPath("$[0].costUsd").value(0.000345))
                .andExpect(jsonPath("$[0].latencyMs").value(850))
                .andExpect(jsonPath("$[1].question").value("older question"))
                .andExpect(jsonPath("$[1].toolNames").isEmpty());
    }

    @Test
    void returnsFiftyRunsByDefaultAndHonoursTheLimit() throws Exception {
        IntStream.range(0, 60).forEach(i -> saveRun("question " + i, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(i), List.of()));

        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(50)))
                .andExpect(jsonPath("$[0].question").value("question 59"));
        mvc.perform(get("/api/admin/runs?limit=3").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void loadsTheToolNamesOfAllRunsWithOneQueryNoMatterHowManyRuns() {
        IntStream.range(0, 20).forEach(i -> saveRun("question " + i, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(i),
                List.of("search_units", "get_payment_history")));
        var statements = new SqlStatementCounter(entityManagerFactory);

        long forThree = statements.during(() -> controller.runs(3, null, null, null, null));
        long forTwenty = statements.during(() -> controller.runs(20, null, null, null, null));

        assertThat(forThree).as("one query for the runs, one for all their tool names").isEqualTo(2);
        assertThat(forTwenty).isEqualTo(forThree);
    }

    @Test
    void rejectsALimitOutsideOneToOneHundred() throws Exception {
        mvc.perform(get("/api/admin/runs?limit=0").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs?limit=101").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs?limit=many").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
    }

    @Test
    void refusesRequestsWithoutTheRightToken() throws Exception {
        saveRun("private question", Instant.parse("2026-10-01T08:00:00Z"), List.of());

        mvc.perform(get("/api/admin/runs")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", "wrong-token-wrong-token-wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", ""))
                .andExpect(status().isForbidden());
    }

    @Test
    void doesNotAcceptTheTokenInTheQueryString() throws Exception {
        mvc.perform(get("/api/admin/runs").queryParam("X-Admin-Token", TOKEN)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs").queryParam("token", TOKEN)).andExpect(status().isForbidden());
    }

    @Test
    void allowsTheFrontendToSendTheTokenHeaderAcrossOrigins() throws Exception {
        mvc.perform(options("/api/admin/runs")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-admin-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "x-admin-token"));
    }

    @Test
    void checksTheTokenBeforeLookingAtAnyOtherParameter() throws Exception {
        mvc.perform(get("/api/admin/runs?limit=0")).andExpect(status().isForbidden());
    }

    private static final String HAIKU = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    @Test
    void listsTheIdAndWhatTheTraceFiguresSay() throws Exception {
        var result = AgentResults.answered(HAIKU, new Usage(120, 45, 3000, 400), 2, 3);
        var id = UUID.randomUUID();
        runs.save(new ChatRun(id, T0, "a question", "anthropic", result, List.of("search_units", "get_unit_details"),
                850, new BigDecimal("0.000345")));
        jdbc.update("update chat_runs set routed_reason = 'complex', guardrail_status = 'FLAGGED', "
                + "guardrail_violations = 1 where id = ?", id);

        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].routedReason").value("complex"))
                .andExpect(jsonPath("$[0].guardrailStatus").value("FLAGGED"))
                .andExpect(jsonPath("$[0].iterations").value(3))
                .andExpect(jsonPath("$[0].inputTokens").value(120))
                .andExpect(jsonPath("$[0].cacheReadTokens").value(3000))
                .andExpect(jsonPath("$[0].cacheWriteTokens").value(400));
    }

    @Test
    void filtersByStatus() throws Exception {
        saveRun("fine", T0, HAIKU, AgentResult.Status.OK, 0);
        saveRun("broken", T0.plusSeconds(1), HAIKU, AgentResult.Status.ERROR, 0);
        saveRun("cut", T0.plusSeconds(2), HAIKU, AgentResult.Status.TRUNCATED, 0);

        mvc.perform(get("/api/admin/runs?status=ERROR").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].question").value("broken"));
    }

    @Test
    void filtersByTheModelThatAnswered() throws Exception {
        saveRun("small", T0, HAIKU, AgentResult.Status.OK, 0);
        saveRun("large", T0.plusSeconds(1), LARGE, AgentResult.Status.OK, 0);

        mvc.perform(get("/api/admin/runs").queryParam("model", LARGE).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].question").value("large"));
    }

    @Test
    void filtersTheRunsTheGuardrailFlagged() throws Exception {
        saveRun("grounded", T0, HAIKU, AgentResult.Status.OK, 0);
        saveRun("flagged", T0.plusSeconds(1), HAIKU, AgentResult.Status.OK, 2);

        mvc.perform(get("/api/admin/runs?guardrailFlagged=true").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].question").value("flagged"));
        mvc.perform(get("/api/admin/runs?guardrailFlagged=false").header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].question").value("grounded"));
    }

    @Test
    void pagesBackwardsFromACursor() throws Exception {
        IntStream.range(0, 5).forEach(i -> saveRun("run " + i, T0.plusSeconds(i), HAIKU, AgentResult.Status.OK, 0));

        mvc.perform(get("/api/admin/runs?limit=2").header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$[*].question").value(contains("run 4", "run 3")));
        mvc.perform(get("/api/admin/runs").queryParam("limit", "2").queryParam("before", T0.plusSeconds(3).toString())
                        .header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$[*].question").value(contains("run 2", "run 1")));
        mvc.perform(get("/api/admin/runs").queryParam("before", T0.toString()).header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void combinesFilters() throws Exception {
        saveRun("small ok", T0, HAIKU, AgentResult.Status.OK, 0);
        saveRun("large ok", T0.plusSeconds(1), LARGE, AgentResult.Status.OK, 0);
        saveRun("large flagged", T0.plusSeconds(2), LARGE, AgentResult.Status.OK, 1);
        saveRun("large flagged late", T0.plusSeconds(3), LARGE, AgentResult.Status.OK, 1);

        mvc.perform(get("/api/admin/runs").queryParam("model", LARGE).queryParam("guardrailFlagged", "true")
                        .queryParam("before", T0.plusSeconds(3).toString()).header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].question").value("large flagged"));
    }

    @Test
    void rejectsFiltersItCannotRead() throws Exception {
        mvc.perform(get("/api/admin/runs?status=NOPE").header("X-Admin-Token", TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs?before=yesterday").header("X-Admin-Token", TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs?guardrailFlagged=perhaps").header("X-Admin-Token", TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/runs").queryParam("model", "m".repeat(65)).header("X-Admin-Token", TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersAreCheckedAfterTheToken() throws Exception {
        mvc.perform(get("/api/admin/runs?status=NOPE")).andExpect(status().isForbidden());
    }

    @Test
    void servesTheTraceOfARunWithItsQuestion() throws Exception {
        var llmCall = new LlmCallRecord(1, HAIKU, 120, 45, 0, 0, 640, StopReason.END_TURN, LlmCallRecord.Outcome.OK, null,
                T0);
        var toolCall = new ToolCallRecord(1, "search_units", "{\"city\":\"Riyadh\"}", "3 units", false, 25, T0);
        var result = new AgentResult("answer", new Usage(120, 45), 1, 1, AgentResult.Status.OK, null, List.of(llmCall),
                List.of(toolCall), ModelRoute.byDefault(HAIKU));
        var id = UUID.randomUUID();
        runs.save(new ChatRun(id, T0, "who is late on rent?", "anthropic", result, List.of("search_units"), 850,
                new BigDecimal("0.000345")));

        mvc.perform(get("/api/admin/runs/{id}", id).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("who is late on rent?"))
                .andExpect(jsonPath("$.runId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.model").value(HAIKU))
                .andExpect(jsonPath("$.totals.inputTokens").value(120))
                .andExpect(jsonPath("$.guardrail.violations").value(0))
                .andExpect(jsonPath("$.llmCalls[0].latencyMs").value(640))
                .andExpect(jsonPath("$.toolCalls[0].seq").value(1))
                .andExpect(jsonPath("$.toolCalls[0].name").value("search_units"))
                .andExpect(jsonPath("$.toolCalls[0].args").doesNotExist())
                .andExpect(jsonPath("$.toolCallDetails", hasSize(1)))
                .andExpect(jsonPath("$.toolCallDetails[0].seq").value(1))
                .andExpect(jsonPath("$.toolCallDetails[0].args").value("{\"city\":\"Riyadh\"}"))
                .andExpect(jsonPath("$.toolCallDetails[0].summary").value("3 units"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void theRunDetailAnswersNotFoundForUnknownAndMalformedIds() throws Exception {
        mvc.perform(get("/api/admin/runs/{id}", UUID.randomUUID()).header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/runs/{id}", "not-a-uuid").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRunDetailNeedsTheTokenToo() throws Exception {
        var id = UUID.randomUUID();
        saveRun("private question", T0, HAIKU, AgentResult.Status.OK, 0);

        mvc.perform(get("/api/admin/runs/{id}", id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/runs/{id}", id).header("X-Admin-Token", "wrong-token-wrong-token-wrong"))
                .andExpect(status().isForbidden());
    }
}
