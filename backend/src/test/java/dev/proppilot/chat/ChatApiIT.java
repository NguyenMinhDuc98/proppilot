package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.admin.DemoResetService;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** End to end through HTTP + SSE with the offline assistant (no API key, no cost). */
@AutoConfigureMockMvc
class ChatApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DemoResetService demo;

    @AfterEach
    void removeWhatTheQuestionsProposed() {
        demo.reset();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The seeded non-payer's unit: a payment and a rent reminder for it are valid. */
    private String unitWithOverdueRent() {
        return jdbc.queryForObject("select u.code from leases l join units u on u.id = l.unit_id where l.tenant_id = 7",
                String.class);
    }

    /** The payload of the first event with this name. */
    private JsonNode eventData(String sse, String name) throws Exception {
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals("event:" + name)) {
                return JSON.readTree(lines[i + 1].substring("data:".length()));
            }
        }
        throw new AssertionError("no " + name + " event in " + sse);
    }

    private String chat(String body) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Concatenates the text of all `token` events, i.e. the answer as the user sees it. */
    private String answerText(String sse) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var answer = new StringBuilder();
        var lines = sse.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals("event:token")) {
                answer.append(json.readTree(lines[i + 1].substring("data:".length())).get("text").asText());
            }
        }
        return answer.toString();
    }

    @Test
    void streamsToolCallToolResultTokensAndDone() throws Exception {
        var sse = chat("{\"message\":\"Who is more than 30 days late on rent?\"}");

        assertThat(sse).contains("event:tool_call").contains("find_overdue_tenants")
                .contains("event:tool_result").contains("event:token")
                .contains("event:done").contains("\"status\":\"OK\"").contains("\"toolCalls\":1")
                .contains("\"provider\":\"offline\"").contains("\"truncated\":false");
        assertThat(answerText(sse)).contains("Ahmed Al-Harbi").doesNotContain("find_overdue_tenants").doesNotContain("Data:");
        assertThat(sse.indexOf("event:tool_call")).isLessThan(sse.indexOf("event:token"));
        assertThat(sse.indexOf("event:token")).isLessThan(sse.indexOf("event:done"));
    }

    @Test
    void answersInArabicForArabicQuestions() throws Exception {
        var sse = chat("{\"message\":\"من المستأجرين المتأخرين في الدفع؟\"}");

        assertThat(sse).contains("find_overdue_tenants");
        assertThat(answerText(sse)).contains("أحمد الحربي").doesNotContain("find_overdue_tenants").doesNotContain("البيانات من");
    }

    @Test
    void aWriteQuestionStreamsTheProposalAsAnActionCard() throws Exception {
        var unit = unitWithOverdueRent();

        var sse = chat("{\"message\":\"Record a payment of 100 for unit " + unit + "\"}");

        assertThat(sse).contains("event:tool_call").contains("record_payment").contains("event:tool_result")
                .contains("event:action_proposed").contains("event:done").contains("\"status\":\"OK\"");
        assertThat(sse.indexOf("event:tool_call")).isLessThan(sse.indexOf("event:tool_result"));
        assertThat(sse.indexOf("event:tool_result")).isLessThan(sse.indexOf("event:action_proposed"));
        assertThat(sse.indexOf("event:action_proposed")).isLessThan(sse.indexOf("event:token"));
        assertThat(sse.indexOf("event:token")).isLessThan(sse.indexOf("event:done"));

        var result = eventData(sse, "tool_result");
        assertThat(result.get("error").asBoolean()).isFalse();
        assertThat(result.get("summary").asText()).startsWith("Proposed, waiting for approval");
        var card = eventData(sse, "action_proposed");
        assertThat(card.get("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(card.get("kind").asText()).isEqualTo("RECORD_PAYMENT");
        assertThat(card.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(card.get("summaryEn").asText()).contains("SAR 100.00", unit);
        assertThat(card.get("summaryAr").asText()).contains("ريال", unit);
        assertThat(card.at("/preview/payments")).isNotEmpty();
        assertThat(card.get("expiresAt").asText()).isNotEmpty();

        assertThat(answerText(sse)).contains("PROPOSED", "waiting for your approval").contains("SAR 100.00");
        var runId = eventData(sse, "done").get("runId").asText();
        assertThat(jdbc.queryForObject("select run_id::text from pending_actions where id = ?::uuid", String.class,
                card.get("id").asText())).isEqualTo(runId);
        assertThat(jdbc.queryForObject("select count(*) from maintenance_requests", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where recorded_action_id is not null",
                Integer.class)).isZero();
    }

    @Test
    void anArabicWriteQuestionIsAnsweredInArabicWithTheSameCard() throws Exception {
        var unit = unitWithOverdueRent();

        var sse = chat("{\"message\":\"سجّل دفعة بمبلغ 100 للوحدة " + unit + "\"}");

        assertThat(sse).contains("event:action_proposed").contains("record_payment");
        assertThat(eventData(sse, "action_proposed").get("status").asText()).isEqualTo("PROPOSED");
        assertThat(answerText(sse)).contains("بانتظار موافقتك", "مقترح").doesNotContain("record_payment");
    }

    @Test
    void aRefusedProposalStreamsAnErrorResultAndNoActionCard() throws Exception {
        var sse = chat("{\"message\":\"Create a maintenance request for unit F-999: broken lock\"}");

        assertThat(sse).contains("event:tool_result").doesNotContain("event:action_proposed").contains("event:done");
        assertThat(eventData(sse, "tool_result").get("error").asBoolean()).isTrue();
        assertThat(answerText(sse)).contains("I could not complete that").contains("There is no unit with code 'F-999'");
        assertThat(jdbc.queryForObject("select count(*) from pending_actions", Integer.class)).isZero();
    }

    @Test
    void readQuestionsStreamNoActionCard() throws Exception {
        var sse = chat("{\"message\":\"Who is more than 30 days late on rent?\"}");

        assertThat(sse).doesNotContain("action_proposed");
    }

    @Test
    void recordsRunsAndExposesStats() throws Exception {
        chat("{\"message\":\"Which units in Jeddah are vacant?\"}");

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.toolUsage[?(@.tool=='search_units')]").exists());
    }

    @Test
    void publicStatsNeverExposeWhatVisitorsAsked() throws Exception {
        chat("{\"message\":\"Which units in Dammam are vacant? (stats privacy marker)\"}");

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRuns").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("privacy marker"))));
    }

    @Test
    void rejectsBlankAndOversizedMessages() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
        var tooLong = "x".repeat(501);
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /** {@code count} alternating turns, starting with the user, as the web app sends them. */
    private static String historyOf(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"role\":\"" + (i % 2 == 0 ? "user" : "assistant") + "\",\"text\":\"turn " + i + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void acceptsAHistoryOfTwentyItems() throws Exception {
        var sse = chat("{\"message\":\"Which units in Riyadh are vacant?\",\"history\":" + historyOf(20) + "}");

        assertThat(sse).contains("event:done");
    }

    @Test
    void rejectsAHistoryLongerThanTwentyItems() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":" + historyOf(21) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("history must have at most 20 items"));
    }

    @Test
    void rejectsMalformedHistory() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":[{\"role\":\"user\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
