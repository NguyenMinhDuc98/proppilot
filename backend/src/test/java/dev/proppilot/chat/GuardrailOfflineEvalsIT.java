package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.admin.DemoResetService;
import dev.proppilot.rag.CorpusIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The guardrail's regression test against false positives: the offline assistant writes every answer from the tool
 * results with templates, so the guardrail, which checks it too, must find nothing to flag in any question of the eval
 * set. A figure, a code, a name or a citation that a template writes and the check does not recognise as the data's
 * own shows up here.
 */
class GuardrailOfflineEvalsIT extends CorpusIntegrationTest {

    private static final Path QUESTIONS = Path.of("..", "evals", "questions.json");
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @Autowired
    MockMvc mvc;
    @Autowired
    DemoResetService demo;

    @AfterEach
    void removeWhatTheQuestionsProposed() {
        demo.reset();
    }

    private SseStream ask(String question) throws Exception {
        var body = new ObjectMapper().createObjectNode().put("message", question).toString();
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(http -> {
                            http.setRemoteAddr("10.23.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return SseStream.of(mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void noQuestionOfTheEvalSetIsFlaggedWhenTheOfflineAssistantAnswersIt() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(QUESTIONS));
        assertThat(cases.size()).isGreaterThanOrEqualTo(42);

        var flagged = new ArrayList<String>();
        for (var item : cases) {
            var sse = ask(item.get("question").asText());
            var done = sse.first("done");
            if (!"OK".equals(done.get("status").asText()) || sse.has("guardrail")
                    || !"OK".equals(done.get("guardrailStatus").asText())) {
                flagged.add(item.get("id").asText() + " " + done.get("status").asText() + " "
                        + done.get("guardrailStatus") + " " + (sse.has("guardrail") ? sse.first("guardrail") : ""));
            }
        }

        assertThat(flagged).as("the questions of the eval set that the guardrail flagged").isEmpty();
    }

    @Test
    void theExamplesTheOfflineAssistantOffersForAQuestionItDoesNotUnderstandAreNotFlagged() throws Exception {
        var english = ask("Tell me a joke about plumbing");
        var arabic = ask("احكِ لي نكتة عن السباكة");

        assertThat(english.answer()).contains("I did not understand that").contains("A-203");
        assertThat(english.has("guardrail")).isFalse();
        assertThat(english.first("done").get("guardrailStatus").asText()).isEqualTo("OK");
        assertThat(arabic.answer()).contains("لم أفهم سؤالك");
        assertThat(arabic.has("guardrail")).isFalse();
        assertThat(arabic.first("done").get("guardrailStatus").asText()).isEqualTo("OK");
    }
}
