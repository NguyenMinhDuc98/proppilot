package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** End to end through HTTP + SSE with the offline assistant (no API key, no cost). */
@AutoConfigureMockMvc
class ChatApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

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
                .contains("\"provider\":\"offline\"");
        assertThat(answerText(sse)).contains("Ahmed Al-Harbi").contains("Data: find_overdue_tenants");
        assertThat(sse.indexOf("event:tool_call")).isLessThan(sse.indexOf("event:token"));
        assertThat(sse.indexOf("event:token")).isLessThan(sse.indexOf("event:done"));
    }

    @Test
    void answersInArabicForArabicQuestions() throws Exception {
        var sse = chat("{\"message\":\"من المستأجرين المتأخرين في الدفع؟\"}");

        assertThat(sse).contains("find_overdue_tenants");
        assertThat(answerText(sse)).contains("أحمد الحربي").contains("البيانات من");
    }

    @Test
    void recordsRunsAndExposesStats() throws Exception {
        chat("{\"message\":\"Which units in Jeddah are vacant?\"}");

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.toolUsage[?(@.tool=='search_units')]").exists())
                .andExpect(jsonPath("$.recentRuns[0].question").exists());
    }

    @Test
    void rejectsBlankAndOversizedMessages() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
        var tooLong = "x".repeat(501);
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedHistory() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"history\":[{\"role\":\"user\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
