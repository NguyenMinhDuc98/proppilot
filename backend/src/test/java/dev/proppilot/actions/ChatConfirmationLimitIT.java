package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A confirmation goes through the chat rate limit, and a refused one has not used up the decision's one confirmation. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {"proppilot.chat.rate-limit-per-minute=1", "proppilot.actions.decisions-per-minute=1000",
        "proppilot.actions.max-approvals-per-day=1000", "proppilot.actions.max-proposals-per-day=100000"})
class ChatConfirmationLimitIT extends ActionIntegrationTest {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder chatFrom(String address, String body) {
        return post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body).with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    private String answerOf(String sse) throws Exception {
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
    void aConfirmationTheLimitRefusedCanStillBeMadeLater() throws Exception {
        var proposed = proposePayment("100");
        actions.approve(proposed.id(), newKey(), edits(), "visitor-test");
        var body = "{\"message\":\"\",\"actionId\":\"" + proposed.id() + "\"}";
        var question = mvc.perform(chatFrom("203.0.113.20", "{\"message\":\"Which units in Riyadh are vacant?\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        question.getAsyncResult(10_000);
        mvc.perform(asyncDispatch(question)).andExpect(status().isOk());

        mvc.perform(chatFrom("203.0.113.20", body)).andExpect(status().isTooManyRequests());

        assertThat(pendingActionRow(proposed.id()).get("confirmed_at")).as("not used up").isNull();
        var later = mvc.perform(chatFrom("203.0.113.21", body)).andExpect(request().asyncStarted()).andReturn();
        later.getAsyncResult(10_000);
        var sse = mvc.perform(asyncDispatch(later)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(answerOf(sse)).contains("Approved and done");
        assertThat(pendingActionRow(proposed.id()).get("confirmed_at")).isNotNull();
    }
}
