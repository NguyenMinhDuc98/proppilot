package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.ScriptedLlmClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The grounding guardrail in retry mode with a larger model configured, through the whole stack. */
@AutoConfigureMockMvc
@Import(ScriptedAnthropic.class)
@TestPropertySource(properties = {"proppilot.guardrail.mode=retry", "proppilot.llm.large-model=" + GuardrailRetryIT.LARGE})
class GuardrailRetryIT extends PostgresIntegrationTest {

    static final String LARGE = "claude-sonnet-4-5";
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptedLlmClient llm;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void freshScript() {
        llm.reset();
    }

    private SseStream ask(String question) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\"}")
                        .with(http -> {
                            http.setRemoteAddr("10.22.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return SseStream.of(mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static UUID runOf(SseStream sse) {
        return UUID.fromString(sse.first("done").get("runId").asText());
    }

    @Test
    void aSecondAnswerThatIsGroundedReplacesTheFirstOnTheLargerModel() throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}")
                .thenText("The portfolio has 151 units.")
                .thenText("The portfolio has 150 units.");

        var sse = ask("How many units are there? (retry ok)");

        assertThat(sse.order()).containsExactly("tool_call", "tool_result", "token", "revise", "token", "done");
        assertThat(sse.first("revise").get("reason").asText()).isEqualTo("grounding");
        assertThat(sse.answer()).isEqualTo("The portfolio has 150 units.");
        assertThat(sse.has("guardrail")).isFalse();
        var done = sse.first("done");
        assertThat(done.get("status").asText()).isEqualTo("OK");
        assertThat(done.get("guardrailStatus").asText()).isEqualTo("RETRIED_OK");
        assertThat(done.get("model").asText()).isEqualTo(LARGE);
        assertThat(done.get("routedReason").asText()).isEqualTo("GUARDRAIL_RETRY");
        assertThat(done.get("iterations").asInt()).isEqualTo(3);
        // tool round: 50 in, 10 out on the default model; the answer and the retry: 100 in, 20 out each, the retry at the
        // larger model's prices: 100 + 200 + 600 per million
        assertThat(done.get("costUsd").decimalValue()).isEqualByComparingTo("0.0009");
        var id = runOf(sse);
        var run = jdbc.queryForMap("""
                select guardrail_status, guardrail_violations, routed_reason, model from chat_runs where id = ?""", id);
        assertThat(run).containsEntry("guardrail_status", "RETRIED_OK").containsEntry("guardrail_violations", 0)
                .containsEntry("routed_reason", "GUARDRAIL_RETRY").containsEntry("model", LARGE);
        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(jsonPath("$.guardrail.status").value("RETRIED_OK"))
                .andExpect(jsonPath("$.llmCalls[*].model").value(contains(ScriptedAnthropic.SMALL, ScriptedAnthropic.SMALL, LARGE)))
                .andExpect(jsonPath("$.totals.inputTokens").value(250));
    }

    @Test
    void aSecondAnswerThatIsStillUngroundedIsDeliveredFlaggedWithItsOwnValues() throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}")
                .thenText("The portfolio has 151 units.")
                .thenText("The portfolio has 152 units.")
                .thenText("never asked for");

        var sse = ask("How many units are there? (retry flagged)");

        assertThat(sse.order()).containsExactly("tool_call", "tool_result", "token", "revise", "token", "guardrail",
                "done");
        assertThat(sse.answer()).isEqualTo("The portfolio has 152 units.");
        assertThat(sse.first("guardrail").at("/values/0/value").asText()).isEqualTo("152");
        assertThat(sse.first("done").get("guardrailStatus").asText()).isEqualTo("RETRIED_FLAGGED");
        assertThat(llm.requests).hasSize(3);
        var id = runOf(sse);
        var run = jdbc.queryForMap("select guardrail_status, guardrail_violations from chat_runs where id = ?", id);
        assertThat(run).containsEntry("guardrail_status", "RETRIED_FLAGGED").containsEntry("guardrail_violations", 1);
        assertThat(jdbc.queryForList("select claim from run_guardrail_findings where run_id = ?", String.class, id))
                .containsExactly("152");
    }

    @Test
    void aGroundedAnswerIsNotAskedForAgain() throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}").thenText("The portfolio has 150 units.");

        var sse = ask("How many units are there? (no retry)");

        assertThat(sse.has("revise")).isFalse();
        assertThat(sse.first("done").get("guardrailStatus").asText()).isEqualTo("OK");
        assertThat(sse.first("done").get("model").asText()).isEqualTo(ScriptedAnthropic.SMALL);
        assertThat(llm.requests).hasSize(2);
    }
}
