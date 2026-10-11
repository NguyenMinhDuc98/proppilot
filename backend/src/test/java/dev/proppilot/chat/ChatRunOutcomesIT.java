package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import dev.proppilot.trace.OpenTelemetryRunTracer;
import dev.proppilot.trace.RunTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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

        @Bean
        InMemorySpanExporter exportedSpans() {
            return InMemorySpanExporter.create();
        }

        @Bean
        @Primary
        RunTracer exportingRunTracer(InMemorySpanExporter exportedSpans) {
            var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exportedSpans)).build();
            return new OpenTelemetryRunTracer(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
        }
    }

    private static final AtomicInteger CLIENTS = new AtomicInteger();

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

    @Autowired
    InMemorySpanExporter exportedSpans;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetScript() {
        llm.reset();
    }

    /** Each question comes from its own address, so the per-client rate limit never decides a test. */
    private String chat(String question) throws Exception {
        var started = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\"}")
                        .with(http -> {
                            http.setRemoteAddr("10.8.0." + CLIENTS.incrementAndGet());
                            return http;
                        }))
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

        var emitter = chatService.start(new ChatRequest("outcome: disconnect", null, null), "visitor-test");
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
    void aClientThatDisconnectsWhileTheOnlyReplyIsStreamingLeavesAnAbortedRunNotAnOkOne() {
        var held = new CountDownLatch(1);
        llm.beforeEachCall(() -> awaitUninterruptibly(held)).thenText("an answer nobody received");

        var emitter = chatService.start(new ChatRequest("outcome: disconnect during the answer", null, null),
                "visitor-test");
        emitter.complete();
        held.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> runExists("outcome: disconnect during the answer"));
        var run = recordedRun("outcome: disconnect during the answer");
        assertThat(run.getStatus()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(run.getInputTokens()).isEqualTo(100);
        assertThat(run.getOutputTokens()).isEqualTo(20);
        assertThat(run.getToolCalls()).isZero();
        assertThat(run.getIterations()).isEqualTo(1);
    }

    @Test
    void theStreamStaysOpenFifteenSecondsLongerThanTheRunDeadline() {
        llm.thenText("hello");

        var emitter = chatService.start(new ChatRequest("outcome: emitter timeout", null, null), "visitor-test");

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

    private ResultActions traceOf(String question) throws Exception {
        return mvc.perform(get("/api/runs/{id}/trace", recordedRun(question).getId())).andExpect(status().isOk());
    }

    @Test
    void storesTheModelCallsAndToolCallsOfARunAsItsTrace() throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}").thenText("There are 150 units.");

        chat("trace: one tool");

        traceOf("trace: one tool")
                .andExpect(jsonPath("$.provider").value("scripted"))
                .andExpect(jsonPath("$.model").value("test"))
                .andExpect(jsonPath("$.totals.inputTokens").value(150))
                .andExpect(jsonPath("$.totals.outputTokens").value(30))
                .andExpect(jsonPath("$.totals.iterations").value(2))
                .andExpect(jsonPath("$.llmCalls", hasSize(2)))
                .andExpect(jsonPath("$.llmCalls[0].seq").value(1))
                .andExpect(jsonPath("$.llmCalls[0].stopReason").value("TOOL_USE"))
                .andExpect(jsonPath("$.llmCalls[0].inputTokens").value(50))
                .andExpect(jsonPath("$.llmCalls[1].seq").value(2))
                .andExpect(jsonPath("$.llmCalls[1].stopReason").value("END_TURN"))
                .andExpect(jsonPath("$.llmCalls[1].outputTokens").value(20))
                .andExpect(jsonPath("$.toolCalls", hasSize(1)))
                .andExpect(jsonPath("$.toolCalls[0].name").value("get_occupancy_summary"))
                .andExpect(jsonPath("$.toolCalls[0].error").value(false));
        var stored = jdbc.queryForMap("select args, summary from run_tool_calls where run_id = ?",
                recordedRun("trace: one tool").getId());
        assertThat(stored).containsEntry("args", "{}");
        assertThat((String) stored.get("summary")).isNotEmpty();
    }

    @Test
    void storesTheCacheTokensApartFromTheUncachedInput() throws Exception {
        llm.then(new LlmResponse(List.of(new ContentBlock.Text("cached")), StopReason.END_TURN, new Usage(30, 12, 4000, 250)));

        chat("trace: cache tokens");

        var run = recordedRun("trace: cache tokens");
        assertThat(run.getInputTokens()).isEqualTo(30);
        assertThat(run.getCacheReadTokens()).isEqualTo(4000);
        assertThat(run.getCacheWriteTokens()).isEqualTo(250);
        traceOf("trace: cache tokens")
                .andExpect(jsonPath("$.totals.inputTokens").value(30))
                .andExpect(jsonPath("$.totals.cacheReadTokens").value(4000))
                .andExpect(jsonPath("$.totals.cacheWriteTokens").value(250))
                .andExpect(jsonPath("$.llmCalls[0].cacheReadTokens").value(4000))
                .andExpect(jsonPath("$.llmCalls[0].cacheWriteTokens").value(250));
    }

    @Test
    void chargesTheCacheTokensOfARunAtTheirOwnPrices() throws Exception {
        llm.then(new LlmResponse(List.of(new ContentBlock.Text("cached")), StopReason.END_TURN, new Usage(30, 12, 4000, 250)));

        var sse = chat("trace: cache cost");

        // 30 x 1.00 + 250 x 1.25 + 4,000 x 0.10 + 12 x 5.00 = 802.5 per million, at the default prices
        assertThat(recordedRun("trace: cache cost").getCostUsd()).isEqualByComparingTo("0.000803");
        assertThat(sse).contains("\"costUsd\":0.000803");
        traceOf("trace: cache cost").andExpect(jsonPath("$.totals.costUsd").value(0.000803));
    }

    @Test
    void storesTheFailedCallOfAFailedRun() throws Exception {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        chat("trace: failure");

        traceOf("trace: failure")
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.llmCalls", hasSize(1)))
                .andExpect(jsonPath("$.llmCalls[0].outcome").value("ERROR"))
                .andExpect(jsonPath("$.llmCalls[0].errorCode").value("llm_overloaded"))
                .andExpect(jsonPath("$.llmCalls[0].stopReason").value(nullValue()))
                .andExpect(jsonPath("$.toolCalls", hasSize(0)));
    }

    @Test
    void aTruncatedReplyKeepsItsStopReasonInTheTrace() throws Exception {
        llm.then(new LlmResponse(List.of(new ContentBlock.Text("The portfolio has 150 un")), StopReason.MAX_TOKENS,
                new Usage(40, 2048)));

        chat("trace: truncated");

        traceOf("trace: truncated")
                .andExpect(jsonPath("$.status").value("TRUNCATED"))
                .andExpect(jsonPath("$.llmCalls[0].stopReason").value("MAX_TOKENS"))
                .andExpect(jsonPath("$.llmCalls[0].outcome").value("OK"));
    }

    @Test
    void aRunTheClientLeftKeepsTheCallsThatHappenedBeforeItWasNoticed() throws Exception {
        var held = new CountDownLatch(1);
        llm.beforeEachCall(() -> awaitUninterruptibly(held))
                .thenToolCall("t1", "get_occupancy_summary", "{}")
                .thenText("never reached");

        var emitter = chatService.start(new ChatRequest("trace: disconnect", null, null), "visitor-test");
        emitter.complete();
        held.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> runExists("trace: disconnect"));
        traceOf("trace: disconnect")
                .andExpect(jsonPath("$.status").value("ABORTED"))
                .andExpect(jsonPath("$.llmCalls", hasSize(1)))
                .andExpect(jsonPath("$.llmCalls[0].outputTokens").value(10))
                .andExpect(jsonPath("$.toolCalls", hasSize(1)))
                .andExpect(jsonPath("$.toolCalls[0].name").value("get_occupancy_summary"));
    }

    @Test
    void longToolArgumentsAreStoredCutOffAtTheCap() throws Exception {
        llm.thenToolCall("t1", "search_units", "{\"city\":\"" + "x".repeat(5000) + "\"}").thenText("none found");

        chat("trace: long arguments");

        var stored = jdbc.queryForMap("select args, summary from run_tool_calls where run_id = ?",
                recordedRun("trace: long arguments").getId());
        assertThat((String) stored.get("args")).hasSize(2000).endsWith("\u2026");
        assertThat(((String) stored.get("summary")).length()).isLessThanOrEqualTo(300);
    }

    @Test
    void aToolTheModelInventedIsAnErrorCallInTheTrace() throws Exception {
        llm.thenToolCall("t1", "no_such_tool", "{\"a\":1}").thenText("sorry");

        chat("trace: unknown tool");

        traceOf("trace: unknown tool")
                .andExpect(jsonPath("$.toolCalls[0].name").value("no_such_tool"))
                .andExpect(jsonPath("$.toolCalls[0].error").value(true));
        assertThat(jdbc.queryForObject("select args from run_tool_calls where run_id = ?", String.class,
                recordedRun("trace: unknown tool").getId())).isEqualTo("{\"a\":1}");
    }

    private List<String> storedToolNames(String question) {
        return jdbc.queryForList("select tool_name from chat_run_tools where run_id = ?", String.class,
                recordedRun(question).getId());
    }

    @Test
    void aToolNameLongerThanTheColumnIsCutOffAndTheRunIsStillRecorded() throws Exception {
        llm.thenToolCall("t1", "t".repeat(100), "{}").thenText("sorry");

        var sse = chat("trace: long tool name");

        assertThat(sse).contains("event:done").doesNotContain("event:error");
        assertThat(recordedRun("trace: long tool name").getStatus()).isEqualTo(AgentResult.Status.OK);
        var name = "t".repeat(63) + "\u2026";
        assertThat(storedToolNames("trace: long tool name")).containsExactly(name);
        traceOf("trace: long tool name").andExpect(jsonPath("$.toolCalls[0].name").value(name));
    }

    @Test
    void aToolNameWithAControlCharacterIsStoredWithASpaceInsteadAndTheRunIsStillRecorded() throws Exception {
        llm.thenToolCall("t1", "bad\u0000name", "{}").thenText("sorry");

        var sse = chat("trace: control character in a tool name");

        assertThat(sse).contains("event:done").doesNotContain("event:error");
        assertThat(storedToolNames("trace: control character in a tool name")).containsExactly("bad name");
    }

    private List<SpanData> spansOf(ChatRun run) {
        var spans = exportedSpans.getFinishedSpanItems();
        var root = spans.stream()
                .filter(span -> run.getId().toString().equals(span.getAttributes().get(
                        AttributeKey.stringKey("run.id"))))
                .findFirst().orElseThrow(() -> new AssertionError("no span was exported for run " + run.getId()));
        return spans.stream().filter(span -> span.getTraceId().equals(root.getTraceId())).toList();
    }

    @Test
    void exportsTheRunAsSpansWhenOpenTelemetryIsOn() throws Exception {
        llm.thenToolCall("t1", "get_occupancy_summary", "{}").thenText("There are 150 units.");

        chat("otel: one tool");

        var spans = spansOf(recordedRun("otel: one tool"));
        assertThat(spans).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("proppilot.run", "chat test", "chat test", "tool get_occupancy_summary");
        var root = spans.stream().filter(span -> span.getName().equals("proppilot.run")).findFirst().orElseThrow();
        assertThat(root.getAttributes().get(AttributeKey.stringKey("run.status")))
                .isEqualTo("OK");
        assertThat(root.getAttributes().get(AttributeKey.longKey("run.iterations")))
                .isEqualTo(2L);
        assertThat(spans.stream().filter(span -> !span.getName().equals("proppilot.run")))
                .allSatisfy(child -> assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId()));
        assertThat(spans.toString()).doesNotContain("otel: one tool").doesNotContain("There are 150 units.");
    }

    @Test
    void exportsAFailedRunWithAnErrorStatus() throws Exception {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        chat("otel: failure");

        var spans = spansOf(recordedRun("otel: failure"));
        assertThat(spans).extracting(span -> span.getStatus().getStatusCode())
                .containsExactlyInAnyOrder(StatusCode.ERROR,
                        StatusCode.ERROR);
        assertThat(spans.toString()).doesNotContain("upstream-secret");
    }
}
