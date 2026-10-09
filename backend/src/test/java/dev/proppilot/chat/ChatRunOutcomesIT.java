package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.ScriptedLlmClient;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.AgentProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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

/** How a chat run ends when the model misbehaves, end to end through HTTP, SSE and the database. */
@AutoConfigureMockMvc
class ChatRunOutcomesIT extends PostgresIntegrationTest {

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
    ChatRunRepository runs;

    @Autowired
    AgentProperties agentProps;

    @BeforeEach
    void resetScript() {
        llm.reset();
    }

    private String chat(String question) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean runExists(String question) {
        return runs.findAll().stream().anyMatch(run -> run.getQuestion().equals(question));
    }

    private ChatRun recordedRun(String question) {
        return runs.findAll().stream().filter(run -> run.getQuestion().equals(question)).findFirst().orElseThrow();
    }

    @Test
    void providerFailuresReachTheBrowserAsACodeWithoutProviderText() throws Exception {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        var sse = chat("outcome: provider failure");

        assertThat(sse).contains("event:error").contains("\"code\":\"llm_overloaded\"")
                .contains("event:done").contains("\"status\":\"ERROR\"")
                .doesNotContain("upstream-secret");
        assertThat(recordedRun("outcome: provider failure").getStatus()).isEqualTo(AgentResult.Status.ERROR);
    }

    @Test
    void aClientThatDisconnectsMidRunStillLeavesAnAbortedRunWithTheTokensSpent() throws Exception {
        var held = new CountDownLatch(1);
        llm.beforeEachCall(() -> awaitUninterruptibly(held))
                .thenToolCall("t1", "get_occupancy_summary", "{}")
                .thenText("never reached");

        var emitter = chatService.start(new ChatRequest("outcome: disconnect", null));
        emitter.complete();
        held.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> runExists("outcome: disconnect"));
        var run = recordedRun("outcome: disconnect");
        assertThat(run.getStatus()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(run.getInputTokens()).isEqualTo(50);
        assertThat(run.getOutputTokens()).isEqualTo(10);
        assertThat(run.getCostUsd()).isPositive();
        assertThat(run.getToolCalls()).isEqualTo(1);
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void theStreamStaysOpenFifteenSecondsLongerThanTheRunDeadline() {
        llm.thenText("hello");

        var emitter = chatService.start(new ChatRequest("outcome: emitter timeout", null));

        assertThat(emitter.getTimeout()).isEqualTo(agentProps.runTimeout().plusSeconds(15).toMillis());
        await().atMost(Duration.ofSeconds(10)).until(() -> runExists("outcome: emitter timeout"));
    }

    @Test
    void aReplyCutOffByTheTokenLimitIsFlaggedTruncatedAndRecorded() throws Exception {
        llm.then(new LlmResponse(List.of(new ContentBlock.Text("The portfolio has 150 un")), StopReason.MAX_TOKENS,
                new Usage(40, 2048)));

        var sse = chat("outcome: truncated");

        assertThat(sse).contains("The portfolio has 150 un")
                .contains("event:done").contains("\"status\":\"TRUNCATED\"").contains("\"truncated\":true");
        var run = recordedRun("outcome: truncated");
        assertThat(run.getStatus()).isEqualTo(AgentResult.Status.TRUNCATED);
        assertThat(run.getOutputTokens()).isEqualTo(2048);
    }
}
