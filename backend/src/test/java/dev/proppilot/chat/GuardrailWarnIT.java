package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.AgentLoop;
import dev.proppilot.agent.ScriptedLlmClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The grounding guardrail in its default mode, warn, through the whole stack: the event and the status of a run whose
 * answer states what nothing supports, what is stored, and who can read it.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ScriptedAnthropic.class)
@TestPropertySource(properties = "proppilot.admin.token=" + GuardrailWarnIT.TOKEN)
class GuardrailWarnIT extends PostgresIntegrationTest {

    static final String TOKEN = "guardrail-warn-admin-token-1234";
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private static final String QUESTION = "How many units does the portfolio have?";

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptedLlmClient llm;
    @Autowired
    JdbcTemplate jdbc;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger loopLogger = (Logger) LoggerFactory.getLogger(AgentLoop.class);

    @BeforeEach
    void freshScriptAndLog() {
        llm.reset();
        log.start();
        loopLogger.addAppender(log);
    }

    @AfterEach
    void releaseTheLog() {
        loopLogger.detachAppender(log);
    }

    private String chat(String question) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\"}")
                        .with(http -> {
                            http.setRemoteAddr("10.20.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The model reads the occupancy of the portfolio, whose result says 150 units, and then answers. */
    private SseStream ask(String question, String answer) throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}").thenText(answer);
        return SseStream.of(chat(question));
    }

    private UUID runOf(SseStream sse) {
        return UUID.fromString(sse.first("done").get("runId").asText());
    }

    private List<String> warnings() {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("Grounding guardrail")).toList();
    }

    @Test
    void aGroundedAnswerIsCheckedOkAndStreamsNoGuardrailEvent() throws Exception {
        var sse = ask(QUESTION + " (grounded)", "The portfolio has 150 units.");

        assertThat(sse.has("guardrail")).isFalse();
        assertThat(sse.first("done").get("guardrailStatus").asText()).isEqualTo("OK");
        var run = jdbc.queryForMap("select guardrail_status, guardrail_violations from chat_runs where id = ?",
                runOf(sse));
        assertThat(run).containsEntry("guardrail_status", "OK").containsEntry("guardrail_violations", 0);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void anUngroundedAnswerIsDeliveredAsItIsAndFlaggedBeforeDone() throws Exception {
        var sse = ask(QUESTION + " (flagged)", "The portfolio has 151 units; unit A-999 is vacant.");

        assertThat(sse.order()).containsExactly("tool_call", "tool_result", "token", "guardrail", "done");
        assertThat(sse.answer()).isEqualTo("The portfolio has 151 units; unit A-999 is vacant.");
        var guardrail = sse.first("guardrail");
        assertThat(guardrail.get("status").asText()).isEqualTo("unverified");
        assertThat(guardrail.get("values")).hasSize(2);
        assertThat(guardrail.at("/values/0/kind").asText()).isEqualTo("NUMBER");
        assertThat(guardrail.at("/values/0/value").asText()).isEqualTo("151");
        assertThat(guardrail.at("/values/1/kind").asText()).isEqualTo("UNIT_CODE");
        assertThat(guardrail.at("/values/1/value").asText()).isEqualTo("A-999");
        assertThat(sse.first("done").get("guardrailStatus").asText()).isEqualTo("FLAGGED");
        assertThat(sse.first("done").get("status").asText()).isEqualTo("OK");
        assertThat(llm.requests).hasSize(2);
    }

    @Test
    void theRunIsStoredFlaggedWithTheCountAndItsValues() throws Exception {
        var sse = ask(QUESTION + " (stored)", "The portfolio has 151 units; unit A-999 is vacant.");

        var id = runOf(sse);
        var run = jdbc.queryForMap("select guardrail_status, guardrail_violations from chat_runs where id = ?", id);
        assertThat(run).containsEntry("guardrail_status", "FLAGGED").containsEntry("guardrail_violations", 2);
        var findings = jdbc.queryForList("select seq, kind, claim from run_guardrail_findings where run_id = ? order by seq",
                id);
        assertThat(findings).extracting(row -> row.get("kind") + ":" + row.get("claim"))
                .containsExactly("NUMBER:151", "UNIT_CODE:A-999");
    }

    @Test
    void oneWarningNamesTheRunTheKindsAndTheCountButNotTheAnswerOrTheQuestion() throws Exception {
        var sse = ask(QUESTION + " (log marker)", "The portfolio has 151 units; unit A-999 is vacant.");

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains(runOf(sse).toString(), "2 value(s)", "NUMBER", "UNIT_CODE")
                .doesNotContain("vacant").doesNotContain("log marker").doesNotContain("portfolio");
    }

    @Test
    void thePublicTraceHoldsTheStatusAndTheCountButNeverTheValues() throws Exception {
        // a figure that no run id, timestamp or duration of the trace can hold by chance
        var sse = ask(QUESTION + " (public trace)", "The portfolio has 8675309 units; unit A-999 is vacant.");

        mvc.perform(get("/api/runs/{id}/trace", runOf(sse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guardrail.status").value("FLAGGED"))
                .andExpect(jsonPath("$.guardrail.violations").value(2))
                .andExpect(jsonPath("$.guardrail.*", hasSize(2)))
                .andExpect(jsonPath("$.guardrailFindings").doesNotExist())
                .andExpect(content().string(not(containsString("A-999"))))
                .andExpect(content().string(not(containsString("8675309"))));
    }

    @Test
    void theAdminSeesTheKindsAndTheValuesInTheDetailAndTheCountInTheList() throws Exception {
        var sse = ask(QUESTION + " (admin)", "The portfolio has 151 units; unit A-999 is vacant.");

        mvc.perform(get("/api/admin/runs/{id}", runOf(sse)).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guardrail.status").value("FLAGGED"))
                .andExpect(jsonPath("$.guardrailFindings", hasSize(2)))
                .andExpect(jsonPath("$.guardrailFindings[0].kind").value("NUMBER"))
                .andExpect(jsonPath("$.guardrailFindings[0].value").value("151"))
                .andExpect(jsonPath("$.guardrailFindings[1].kind").value("UNIT_CODE"))
                .andExpect(jsonPath("$.guardrailFindings[1].value").value("A-999"));
        mvc.perform(get("/api/admin/runs?guardrailFlagged=true").header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$[?(@.id=='" + runOf(sse) + "')].guardrailStatus").value("FLAGGED"))
                .andExpect(jsonPath("$[?(@.id=='" + runOf(sse) + "')].guardrailViolations").value(2));
    }

    @Test
    void aRunWithNothingFlaggedHasNoFindings() throws Exception {
        var sse = ask(QUESTION + " (clean admin)", "The portfolio has 150 units.");

        mvc.perform(get("/api/admin/runs/{id}", runOf(sse)).header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.guardrail.status").value("OK"))
                .andExpect(jsonPath("$.guardrailFindings", hasSize(0)));
    }

    @Test
    void aMadeUpCitationIsFlaggedAsOne() throws Exception {
        var sse = ask(QUESTION + " (citation)", "The portfolio has 150 units [LSE-A-204#4].");

        assertThat(sse.first("guardrail").at("/values/0/kind").asText()).isEqualTo("CITATION");
        assertThat(sse.first("guardrail").at("/values/0/value").asText()).isEqualTo("LSE-A-204#4");
    }
}
