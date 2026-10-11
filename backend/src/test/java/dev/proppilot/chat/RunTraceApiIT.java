package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.ToolCallRecord;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.rag.CorpusIntegrationTest;
import dev.proppilot.trace.NoopRunTracer;
import dev.proppilot.trace.RunTracer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The public trace of a run, from rows that were stored and from a question the offline assistant answered. */
class RunTraceApiIT extends CorpusIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final String QUESTION = "private question marker: who owes rent in B-12?";
    private static final String ANSWER = "private answer marker: the tenant of B-12 owes SAR 4,800.";

    @Autowired
    MockMvc mvc;
    @Autowired
    ChatRunRepository runs;
    @Autowired
    ObjectMapper json;
    @Autowired
    RunTracer tracer;

    private UUID save(AgentResult result) {
        var id = UUID.randomUUID();
        runs.save(new ChatRun(id, T0.plusSeconds(2), QUESTION, "anthropic", MODEL, result, List.of("search_units"), 1400,
                new BigDecimal("0.001234")));
        return id;
    }

    private static AgentResult aRunThatSearchedOnceWithTheCache() {
        var first = new LlmCallRecord(1, MODEL, 1200, 45, 0, 3000, 640, StopReason.TOOL_USE, LlmCallRecord.Outcome.OK, null,
                T0);
        var second = new LlmCallRecord(2, MODEL, 40, 160, 3000, 0, 710, StopReason.END_TURN, LlmCallRecord.Outcome.OK, null,
                T0.plusMillis(700));
        var tool = new ToolCallRecord(1, "search_units", "{\"city\":\"Riyadh\"}", "3 units found", false, 25,
                T0.plusMillis(650));
        return new AgentResult(ANSWER, new Usage(1240, 205, 3000, 3000), 1, 2, AgentResult.Status.OK, null,
                List.of(first, second), List.of(tool));
    }

    @Test
    void servesTheStoredTraceOfARunWithoutAnyToken() throws Exception {
        var id = save(aRunThatSearchedOnceWithTheCache());

        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.runId").value(id.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-01T08:00:02Z"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.routedReason").value(nullValue()))
                .andExpect(jsonPath("$.guardrail.status").value(nullValue()))
                .andExpect(jsonPath("$.guardrail.violations").value(0))
                .andExpect(jsonPath("$.totals.inputTokens").value(1240))
                .andExpect(jsonPath("$.totals.outputTokens").value(205))
                .andExpect(jsonPath("$.totals.cacheReadTokens").value(3000))
                .andExpect(jsonPath("$.totals.cacheWriteTokens").value(3000))
                .andExpect(jsonPath("$.totals.costUsd").value(0.001234))
                .andExpect(jsonPath("$.totals.latencyMs").value(1400))
                .andExpect(jsonPath("$.totals.iterations").value(2))
                .andExpect(jsonPath("$.totals.toolCalls").value(1))
                .andExpect(jsonPath("$.llmCalls", hasSize(2)))
                .andExpect(jsonPath("$.llmCalls[0].seq").value(1))
                .andExpect(jsonPath("$.llmCalls[0].model").value(MODEL))
                .andExpect(jsonPath("$.llmCalls[0].inputTokens").value(1200))
                .andExpect(jsonPath("$.llmCalls[0].outputTokens").value(45))
                .andExpect(jsonPath("$.llmCalls[0].cacheReadTokens").value(0))
                .andExpect(jsonPath("$.llmCalls[0].cacheWriteTokens").value(3000))
                .andExpect(jsonPath("$.llmCalls[0].latencyMs").value(640))
                .andExpect(jsonPath("$.llmCalls[0].stopReason").value("TOOL_USE"))
                .andExpect(jsonPath("$.llmCalls[0].outcome").value("OK"))
                .andExpect(jsonPath("$.llmCalls[0].errorCode").value(nullValue()))
                .andExpect(jsonPath("$.llmCalls[0].startedAt").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.llmCalls[1].seq").value(2))
                .andExpect(jsonPath("$.llmCalls[1].cacheReadTokens").value(3000))
                .andExpect(jsonPath("$.llmCalls[1].stopReason").value("END_TURN"))
                .andExpect(jsonPath("$.toolCalls", hasSize(1)))
                .andExpect(jsonPath("$.toolCalls[0].seq").value(1))
                .andExpect(jsonPath("$.toolCalls[0].name").value("search_units"))
                .andExpect(jsonPath("$.toolCalls[0].args").value("{\"city\":\"Riyadh\"}"))
                .andExpect(jsonPath("$.toolCalls[0].summary").value("3 units found"))
                .andExpect(jsonPath("$.toolCalls[0].error").value(false))
                .andExpect(jsonPath("$.toolCalls[0].latencyMs").value(25));
    }

    @Test
    void theApplicationStartsWithNoTracingUnlessItIsSwitchedOn() {
        assertThat(tracer).isInstanceOf(NoopRunTracer.class);
    }

    @Test
    void neverCarriesTheQuestionOrTheAnswer() throws Exception {
        var id = save(aRunThatSearchedOnceWithTheCache());

        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").doesNotExist())
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(content().string(not(containsString("private question marker"))))
                .andExpect(content().string(not(containsString("private answer marker"))));
    }

    @Test
    void aFailedCallShowsItsCodeAndNoProviderText() throws Exception {
        var failed = new LlmCallRecord(1, MODEL, 0, 0, 0, 0, 5200, null, LlmCallRecord.Outcome.ERROR,
                ErrorCode.LLM_OVERLOADED, T0);
        var id = save(AgentResult.failed(ErrorCode.LLM_OVERLOADED, Usage.ZERO, 0, 1, List.of(failed),
                List.of()));

        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.llmCalls[0].outcome").value("ERROR"))
                .andExpect(jsonPath("$.llmCalls[0].errorCode").value("llm_overloaded"))
                .andExpect(jsonPath("$.llmCalls[0].stopReason").value(nullValue()))
                .andExpect(jsonPath("$.toolCalls", hasSize(0)));
    }

    @Test
    void showsWhatTheGuardrailAndTheRouterRecordedForTheRun() throws Exception {
        var id = save(aRunThatSearchedOnceWithTheCache());
        jdbc.update("update chat_runs set routed_reason = 'complex', guardrail_status = 'FLAGGED', "
                + "guardrail_violations = 2 where id = ?", id);

        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routedReason").value("complex"))
                .andExpect(jsonPath("$.guardrail.status").value("FLAGGED"))
                .andExpect(jsonPath("$.guardrail.violations").value(2));
    }

    @Test
    void aRunStoredBeforeTracesExistedHasItsTotalsAndNoCalls() throws Exception {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into chat_runs (id, created_at, question, provider, model, input_tokens, output_tokens, tool_calls,
                                       iterations, latency_ms, cost_usd, status)
                values (?, ?::timestamptz, 'old question', 'offline', 'rule-based', 100, 20, 1, 2, 80, 0, 'OK')""",
                id, "2026-09-01T08:00:00Z");

        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.inputTokens").value(100))
                .andExpect(jsonPath("$.totals.cacheReadTokens").value(0))
                .andExpect(jsonPath("$.totals.iterations").value(2))
                .andExpect(jsonPath("$.llmCalls", hasSize(0)))
                .andExpect(jsonPath("$.toolCalls", hasSize(0)));
    }

    @Test
    void answersNotFoundForARunThatDoesNotExist() throws Exception {
        mvc.perform(get("/api/runs/{id}/trace", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-uuid",
            "1-1-1-1-1",
            "00000000-0000-0000-0000-00000000000g",
            "00000000000000000000000000000000",
            "00000000-0000-0000-0000-0000000000011",
            "' or 1=1 --",
            "../../admin/runs"})
    void answersNotFoundForAnIdThatIsNotAUuid(String id) throws Exception {
        mvc.perform(get("/api/runs/{id}/trace", id)).andExpect(status().isNotFound());
    }

    @Test
    void answersNotFoundForAVeryLongId() throws Exception {
        mvc.perform(get("/api/runs/{id}/trace", "a".repeat(20_000))).andExpect(status().isNotFound());
    }

    private static final AtomicInteger CLIENTS = new AtomicInteger();

    private String chat(String question) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", question)))
                        .with(http -> {
                            http.setRemoteAddr("10.7.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private UUID runIdOf(String sse) throws Exception {
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals("event:done")) {
                return UUID.fromString(json.readTree(lines[i + 1].substring("data:".length())).get("runId").asText());
            }
        }
        throw new AssertionError("no done event in " + sse);
    }

    @Test
    void aQuestionTheOfflineAssistantAnsweredCanBeReadBackAsATrace() throws Exception {
        var sse = chat("Which units in Jeddah are vacant? (offline trace)");
        var id = runIdOf(sse);

        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.provider").value("offline"))
                .andExpect(jsonPath("$.model").value("rule-based"))
                .andExpect(jsonPath("$.totals.cacheReadTokens").value(0))
                .andExpect(jsonPath("$.totals.cacheWriteTokens").value(0))
                .andExpect(jsonPath("$.totals.costUsd").value(0))
                .andExpect(jsonPath("$.totals.iterations").value(2))
                .andExpect(jsonPath("$.llmCalls", hasSize(2)))
                .andExpect(jsonPath("$.llmCalls[0].model").value("rule-based"))
                .andExpect(jsonPath("$.llmCalls[0].stopReason").value("TOOL_USE"))
                .andExpect(jsonPath("$.llmCalls[0].inputTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.llmCalls[1].stopReason").value("END_TURN"))
                .andExpect(jsonPath("$.toolCalls", hasSize(1)))
                .andExpect(jsonPath("$.toolCalls[0].name").value("search_units"))
                .andExpect(jsonPath("$.toolCalls[0].error").value(false))
                .andExpect(jsonPath("$.toolCalls[0].summary").isNotEmpty())
                .andExpect(content().string(not(containsString("offline trace"))));
    }

    @Test
    void theStoredRowsMatchWhatTheStreamReported() throws Exception {
        var sse = chat("Which units in Jeddah are vacant? (rows)");
        var id = runIdOf(sse);

        var llmCalls = jdbc.queryForList("select seq, model, outcome from run_llm_calls where run_id = ? order by seq", id);
        var toolCalls = jdbc.queryForList("select seq, tool_name from run_tool_calls where run_id = ? order by seq", id);
        var iterations = jdbc.queryForObject("select iterations from chat_runs where id = ?", Integer.class, id);

        assertThat(llmCalls).hasSize(iterations).extracting(row -> row.get("outcome")).containsOnly("OK");
        assertThat(llmCalls).extracting(row -> row.get("seq")).containsExactly(1, 2);
        assertThat(toolCalls).extracting(row -> row.get("seq")).containsExactly(1);
    }
}
