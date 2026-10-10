package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.ScriptedLlmClient;
import dev.proppilot.chat.ChatRequest;
import dev.proppilot.chat.ChatService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A decision is confirmed once, but only a run that delivers the confirmation uses it up: when the model fails, the run
 * breaks or the client leaves, the person can ask for the confirmation again.
 */
@AutoConfigureMockMvc
class ChatConfirmationOutcomesIT extends ActionIntegrationTest {

    private static final String DECIDER = "visitor-decider";

    @TestConfiguration
    static class ScriptedLlmConfig {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlm() {
            return new ScriptedLlmClient();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptedLlmClient llm;
    @Autowired
    ChatService chatService;
    @Autowired
    VisitorActors visitors;

    @BeforeEach
    void resetScript() {
        llm.reset();
    }

    private UUID approvedBy(String actor) {
        var proposed = proposePayment("100");
        actions.approve(proposed.id(), newKey(), edits(), actor);
        return proposed.id();
    }

    private String confirmationFrom(String address, UUID id) throws Exception {
        var body = "{\"message\":\"\",\"actionId\":\"" + id + "\"}";
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })).andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private boolean isConfirmed(UUID id) {
        return pendingActionRow(id).get("confirmed_at") != null;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aModelFailureGivesTheConfirmationBackSoTheSameRequestCanBeRetried() throws Exception {
        var address = "198.51.100.77";
        var id = approvedBy(visitors.actorFor(address));
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529");

        var failed = confirmationFrom(address, id);

        assertThat(failed).contains("event:error").contains("\"code\":\"llm_overloaded\"");
        assertThat(isConfirmed(id)).as("given back").isFalse();

        llm.thenText("Your payment was recorded.");
        var retried = confirmationFrom(address, id);

        assertThat(retried).contains("Your payment was recorded.").contains("\"status\":\"OK\"");
        assertThat(isConfirmed(id)).isTrue();
    }

    @Test
    void aModelTimeoutGivesTheConfirmationBack() throws Exception {
        var address = "198.51.100.78";
        var id = approvedBy(visitors.actorFor(address));
        llm.thenFail(ErrorCode.LLM_TIMEOUT, "timed out");

        assertThat(confirmationFrom(address, id)).contains("\"code\":\"llm_timeout\"");

        assertThat(isConfirmed(id)).isFalse();
    }

    @Test
    void aClientThatLeavesBeforeTheConfirmationArrivesGivesItBack() {
        var id = approvedBy(DECIDER);
        var held = new CountDownLatch(1);
        llm.beforeEachCall(() -> awaitUninterruptibly(held)).thenText("a confirmation nobody received");

        var emitter = chatService.start(new ChatRequest("", null, id), DECIDER);
        assertThat(isConfirmed(id)).as("taken while the run is going").isTrue();
        emitter.complete();
        held.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> !isConfirmed(id));

        llm.reset();
        llm.thenText("Done.");
        chatService.start(new ChatRequest("", null, id), DECIDER);
        await().atMost(Duration.ofSeconds(10)).until(() -> isConfirmed(id));
    }

    @Test
    void aRunThatBreaksGivesTheConfirmationBack() {
        var id = approvedBy(DECIDER);
        var held = new CountDownLatch(1);
        // Nothing is scripted, so the model call fails with an exception that is not an LlmException.
        llm.beforeEachCall(() -> awaitUninterruptibly(held));

        chatService.start(new ChatRequest("", null, id), DECIDER);
        assertThat(isConfirmed(id)).as("taken while the run is going").isTrue();
        held.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> !isConfirmed(id));
    }

    @Test
    void aConfirmationThatWasDeliveredStaysUsedUp() throws Exception {
        var address = "198.51.100.79";
        var id = approvedBy(visitors.actorFor(address));
        llm.thenText("Your payment was recorded.");

        confirmationFrom(address, id);

        assertThat(isConfirmed(id)).isTrue();
        var body = "{\"message\":\"\",\"actionId\":\"" + id + "\"}";
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })).andExpect(status().isConflict());
    }
}
