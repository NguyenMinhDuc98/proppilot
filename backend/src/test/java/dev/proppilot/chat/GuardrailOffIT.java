package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** With the guardrail off nothing is checked: no event, no status, nothing stored. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ScriptedAnthropic.class)
@TestPropertySource(properties = "proppilot.guardrail.mode=off")
class GuardrailOffIT extends PostgresIntegrationTest {

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

    @Test
    void anAnswerThatStatesWhatNothingSupportsIsNotCheckedFlaggedOrStored() throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}")
                .thenText("The portfolio has 151 units; unit A-999 is vacant [LSE-A-999#4].");

        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How many units are there? (guardrail off)\"}")
                        .with(http -> {
                            http.setRemoteAddr("10.21.0.1");
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        var text = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        var sse = SseStream.of(text);
        assertThat(sse.has("guardrail")).isFalse();
        assertThat(sse.first("done").get("guardrailStatus").isNull()).isTrue();
        assertThat(sse.first("done").get("status").asText()).isEqualTo("OK");
        var id = UUID.fromString(sse.first("done").get("runId").asText());
        var run = jdbc.queryForMap("select guardrail_status, guardrail_violations from chat_runs where id = ?", id);
        assertThat(run).containsEntry("guardrail_status", null).containsEntry("guardrail_violations", 0);
        assertThat(jdbc.queryForObject("select count(*) from run_guardrail_findings where run_id = ?", Integer.class, id))
                .isZero();
        mvc.perform(get("/api/runs/{id}/trace", id))
                .andExpect(jsonPath("$.guardrail.status").doesNotExist())
                .andExpect(jsonPath("$.guardrail.violations").value(0));
    }
}
