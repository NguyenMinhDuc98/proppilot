package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ComplexityAssessor;
import dev.proppilot.agent.routing.ModelRouter;
import dev.proppilot.agent.tools.ProposalTool;
import dev.proppilot.agent.tools.Tool;
import dev.proppilot.agent.tools.ToolContext;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.agent.tools.ToolSchema;
import dev.proppilot.config.AgentProperties;
import dev.proppilot.config.TestLlmProperties;
import dev.proppilot.guardrail.Guardrails;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class AgentLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(90);
    private static final BooleanSupplier NEVER = () -> false;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-15T10:00:00Z"));
    private final List<String> executedWith = new ArrayList<>();
    private Duration echoTakes = Duration.ZERO;

    private final Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "Echoes its input"; }
        public JsonNode inputSchema() { return ToolSchema.object().string("text", "text").required("text").build(); }
        public ToolResult execute(JsonNode input) {
            if (!input.hasNonNull("text")) {
                throw new ToolInputException("text is required");
            }
            executedWith.add(input.get("text").asText());
            clock.advance(echoTakes);
            return ToolResult.ok(JSON, java.util.Map.of("echo", input.get("text").asText()), "echoed");
        }
    };

    private static AgentRequest ask(String question, List<ChatTurn> history) {
        return AgentRequest.question(question, history, UUID.randomUUID());
    }

    private static final JsonNode CARD = JSON.createObjectNode().put("type", "action").put("secret-card-field", "x");
    private final List<ToolContext> proposalContexts = new ArrayList<>();

    /** Stands in for a write tool: it stores nothing and hands the web app a card the model must never see. */
    private final ProposalTool propose = new ProposalTool() {
        public String name() { return "propose"; }
        public String description() { return "Proposes a change"; }
        public JsonNode inputSchema() { return ToolSchema.object().build(); }
        public ToolResult propose(JsonNode input, ToolContext context) {
            proposalContexts.add(context);
            return ToolResult.ok(JSON, java.util.Map.of("status", "PROPOSED"), "proposed").withUi(CARD);
        }
    };

    /** Stands in for a tool that costs something on every call, so it may be called twice in a reply. */
    private final Tool metered = new Tool() {
        public String name() { return "metered"; }
        public String description() { return "Costs something per call"; }
        public JsonNode inputSchema() { return ToolSchema.object().string("text", "text").build(); }
        public ToolResult execute(JsonNode input) {
            executedWith.add("metered:" + input.path("text").asText());
            return ToolResult.ok(JSON, java.util.Map.of("ran", true), "ran");
        }
        @Override
        public OptionalInt maxCallsPerRun() { return OptionalInt.of(2); }
    };

    private AgentLoop loop(ScriptedLlmClient llm, int maxIterations, Tool... more) {
        var tools = new ArrayList<Tool>(List.of(echo));
        tools.addAll(List.of(more));
        var registry = new ToolRegistry(tools, mock(PlatformTransactionManager.class));
        var router = new ModelRouter(llm, TestLlmProperties.defaults().build(), new ComplexityAssessor());
        return new AgentLoop(llm, registry, new AgentProperties(maxIterations, 10, (int) RUN_TIMEOUT.toSeconds()), clock,
                router, Guardrails.off());
    }

    @Test
    void answersDirectlyWhenModelRequestsNoTools() {
        var llm = new ScriptedLlmClient().thenText("Hello");
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run(ask("hi", List.of()), events::add, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo("Hello");
        assertThat(result.toolCalls()).isZero();
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(events).containsExactly(new AgentEvent.Token("Hello"));
    }

    @Test
    void executesRequestedToolAndFeedsResultBackToModel() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{\"text\":\"ping\"}")
                .thenText("Done: ping");
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run(ask("say ping", List.of()), events::add, NEVER);

        assertThat(executedWith).containsExactly("ping");
        assertThat(result.answer()).isEqualTo("Done: ping");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.usage().inputTokens()).isEqualTo(150);
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResultEvent", "Token");

        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.toolUseId()).isEqualTo("t1");
        assertThat(toolResult.content()).contains("ping");
        assertThat(toolResult.error()).isFalse();
    }

    @Test
    void tellsTheModelClientWhichCallOfTheRunItIsMaking() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{\"text\":\"one\"}")
                .thenToolCall("t2", "echo", "{\"text\":\"two\"}")
                .thenText("Done");

        var result = loop(llm, 6).run(ask("say twice", List.of()), event -> { }, NEVER);

        assertThat(result.iterations()).isEqualTo(3);
        assertThat(llm.requests).extracting(LlmRequest::iteration).containsExactly(1, 2, 3);
    }

    @Test
    void invalidToolInputIsReturnedToTheModelAsAnError() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{}")
                .thenText("Sorry, I need text");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.error()).isTrue();
        assertThat(toolResult.content()).contains("text is required");
    }

    @Test
    void unknownToolIsAnErrorResultNotAnException() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "does_not_exist", "{}")
                .thenText("ok");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.error()).isTrue();
        assertThat(toolResult.content()).contains("Unknown tool").contains("echo");
    }

    @Test
    void stopsAfterMaxIterations() {
        var llm = new ScriptedLlmClient();
        for (int i = 0; i < 3; i++) {
            llm.thenToolCall("t" + i, "echo", "{\"text\":\"again\"}");
        }
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 3).run(ask("loop forever", List.of()), events::add, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(llm.requests).hasSize(3);
        assertThat(events.get(events.size() - 1)).isEqualTo(new AgentEvent.Token(AgentLoop.GAVE_UP));
    }

    @Test
    void llmFailureEndsTheRunWithAnErrorCodeAndNoProviderText() {
        var llm = new ScriptedLlmClient().thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(result.answer()).isEqualTo(ErrorCode.LLM_OVERLOADED.message()).doesNotContain("upstream-secret");
    }

    @Test
    void aReplyCutOffByTheTokenLimitEndsTheRunAsTruncatedAndRunsNoTools() throws Exception {
        var toolCall = new ContentBlock.ToolUse("t1", "echo", JSON.readTree("{\"text\":\"ping\"}"));
        var llm = new ScriptedLlmClient().then(new LlmResponse(
                List.of(new ContentBlock.Text("The answer is cut"), toolCall), StopReason.MAX_TOKENS, new Usage(30, 2048)));
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run(ask("go", List.of()), events::add, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.TRUNCATED);
        assertThat(result.answer()).isEqualTo("The answer is cut");
        assertThat(result.usage()).isEqualTo(new Usage(30, 2048));
        assertThat(result.toolCalls()).isZero();
        assertThat(executedWith).isEmpty();
        assertThat(llm.requests).hasSize(1);
        assertThat(events).noneMatch(AgentEvent.ToolCall.class::isInstance);
    }

    @Test
    void cancelledBeforeTheFirstCallNothingIsSentToTheModel() {
        var llm = new ScriptedLlmClient().thenText("never asked");

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, () -> true);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(result.usage()).isEqualTo(Usage.ZERO);
        assertThat(result.iterations()).isZero();
        assertThat(llm.requests).isEmpty();
    }

    @Test
    void cancelledWhileTheModelWasAnsweringKeepsTheTokensSpentAndRunsNoTool() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{\"text\":\"ping\"}")
                .thenText("never reached");
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run(ask("go", List.of()), events::add, () -> !llm.requests.isEmpty());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(result.usage()).isEqualTo(new Usage(50, 10));
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(executedWith).isEmpty();
        assertThat(events).isEmpty();
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void cancelledWhileTheFinalReplyWasStreamingEndsTheRunAsAbortedNotOk() {
        var llm = new ScriptedLlmClient().thenText("an answer nobody received");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, () -> !llm.requests.isEmpty());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(result.answer()).isEmpty();
        assertThat(result.usage()).isEqualTo(new Usage(100, 20));
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    void cancelledDuringTheLastToolCallEndsTheRunAsAbortedNotGaveUp() {
        var llm = new ScriptedLlmClient().thenToolCall("t1", "echo", "{\"text\":\"ping\"}");

        var result = loop(llm, 1).run(ask("go", List.of()), e -> { }, () -> !executedWith.isEmpty());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    void cancelledBetweenToolsStopsBeforeTheNextOneAndCountsTheOnesThatRan() throws Exception {
        var first = new ContentBlock.ToolUse("t1", "echo", JSON.readTree("{\"text\":\"one\"}"));
        var second = new ContentBlock.ToolUse("t2", "echo", JSON.readTree("{\"text\":\"two\"}"));
        var llm = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(first, second), StopReason.TOOL_USE, new Usage(50, 10)))
                .thenText("never reached");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, () -> !executedWith.isEmpty());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(executedWith).containsExactly("one");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.usage()).isEqualTo(new Usage(50, 10));
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void everyModelCallGetsOnlyTheTimeLeftAndTheRunEndsWithRunTimeoutWhenItIsUsedUp() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(Duration.ofSeconds(60)))
                .thenToolCall("t1", "echo", "{\"text\":\"one\"}")
                .thenToolCall("t2", "echo", "{\"text\":\"two\"}")
                .thenText("never reached");

        var result = loop(llm, 6).run(ask("slow question", List.of()), e -> { }, NEVER);

        assertThat(llm.requests).extracting(LlmRequest::timeout)
                .containsExactly(Duration.ofSeconds(90), Duration.ofSeconds(30));
        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.RUN_TIMEOUT);
        assertThat(result.answer()).isEqualTo(ErrorCode.RUN_TIMEOUT.message());
        assertThat(result.usage()).isEqualTo(new Usage(100, 20));
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(executedWith).containsExactly("one");
    }

    @Test
    void aModelCallThatTimesOutAfterTheRunDeadlineEndsTheRunWithRunTimeout() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(RUN_TIMEOUT.plusSeconds(1)))
                .thenFail(ErrorCode.LLM_TIMEOUT, "Claude response stream timed out");

        var result = loop(llm, 6).run(ask("slow question", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.RUN_TIMEOUT);
        assertThat(result.answer()).isEqualTo(ErrorCode.RUN_TIMEOUT.message());
    }

    @Test
    void aModelCallThatTimesOutWithTimeLeftInTheRunStaysAnLlmTimeout() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(Duration.ofSeconds(60)))
                .thenFail(ErrorCode.LLM_TIMEOUT, "Claude response stream timed out");

        var result = loop(llm, 6).run(ask("slow question", List.of()), e -> { }, NEVER);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_TIMEOUT);
    }

    @Test
    void otherModelFailuresKeepTheirCodeEvenWhenTheDeadlineHasPassed() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(RUN_TIMEOUT.plusSeconds(1)))
                .thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 429");

        var result = loop(llm, 6).run(ask("busy", List.of()), e -> { }, NEVER);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
    }

    @Test
    void theDeadlineIsAlsoCheckedBetweenToolCalls() throws Exception {
        var first = new ContentBlock.ToolUse("t1", "echo", JSON.readTree("{\"text\":\"one\"}"));
        var second = new ContentBlock.ToolUse("t2", "echo", JSON.readTree("{\"text\":\"two\"}"));
        var llm = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(first, second), StopReason.TOOL_USE, new Usage(50, 10)));
        echoTakes = RUN_TIMEOUT.plusSeconds(1);

        var result = loop(llm, 6).run(ask("slow tools", List.of()), e -> { }, NEVER);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.RUN_TIMEOUT);
        assertThat(executedWith).containsExactly("one");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void aRunThatFinishesInTimeIsNotAffectedByTheDeadline() {
        var llm = new ScriptedLlmClient().beforeEachCall(() -> clock.advance(Duration.ofSeconds(89))).thenText("fast enough");

        var result = loop(llm, 6).run(ask("quick", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
    }

    @Test
    void sendsSystemPromptWithTodaysDateAndToolSpecs() {
        var llm = new ScriptedLlmClient().thenText("ok");

        loop(llm, 6).run(ask("hi", List.of()), e -> { }, NEVER);

        var request = llm.requests.get(0);
        assertThat(request.system()).contains("2026-03-15").contains("Never invent");
        assertThat(request.tools()).extracting(t -> t.name()).containsExactly("echo");
    }

    @Test
    void historyIsTrimmedAndKeepsRolesAlternating() {
        var llm = new ScriptedLlmClient().thenText("ok");
        var history = List.of(
                new ChatTurn("assistant", "stale greeting"),
                new ChatTurn("user", "first question"),
                new ChatTurn("assistant", "first answer"),
                new ChatTurn("user", "dangling question without answer"));

        loop(llm, 6).run(ask("second question", history), e -> { }, NEVER);

        var messages = llm.requests.get(0).messages();
        assertThat(messages).extracting(Message::role).containsExactly(
                Message.Role.USER, Message.Role.ASSISTANT, Message.Role.USER);
        assertThat(((ContentBlock.Text) messages.get(2).content().get(0)).text()).isEqualTo("second question");
    }

    @Test
    void whatAToolHandsTheWebAppReachesTheEventsAndNeverTheModel() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "propose", "{}")
                .thenText("Waiting for your approval");
        var events = new ArrayList<AgentEvent>();

        loop(llm, 6, propose).run(ask("record it", List.of()), events::add, NEVER);

        var resultEvent = events.stream().filter(AgentEvent.ToolResultEvent.class::isInstance)
                .map(AgentEvent.ToolResultEvent.class::cast).findFirst().orElseThrow();
        assertThat(resultEvent.ui()).isEqualTo(CARD);
        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.content()).contains("PROPOSED").doesNotContain("secret-card-field");
        assertThat(llm.requests.toString()).doesNotContain("secret-card-field");
    }

    @Test
    void aProposalToolKnowsTheRunItBelongsTo() {
        var runId = UUID.randomUUID();
        var llm = new ScriptedLlmClient().thenToolCall("t1", "propose", "{}").thenText("ok");

        loop(llm, 6, propose).run(AgentRequest.question("record it", List.of(), runId), e -> { }, NEVER);

        assertThat(proposalContexts).containsExactly(new ToolContext(runId));
    }

    @Test
    void aConfirmationOffersNoToolsAndRunsNoneEvenWhenTheModelAsksForOne() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "propose", "{}")
                .thenText("Done, nothing else");

        var result = loop(llm, 6, propose).run(
                AgentRequest.confirmation("[PROPOSAL DECISION] approved", List.of(), UUID.randomUUID()), e -> { }, NEVER);

        assertThat(llm.requests.get(0).tools()).isEmpty();
        assertThat(proposalContexts).isEmpty();
        assertThat(executedWith).isEmpty();
        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.error()).isTrue();
        assertThat(toolResult.content()).contains("No tools are available");
        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
    }

    @Test
    void aQuestionOffersEveryTool() {
        var llm = new ScriptedLlmClient().thenText("ok");

        loop(llm, 6, propose).run(ask("hi", List.of()), e -> { }, NEVER);

        assertThat(llm.requests.get(0).tools()).extracting(t -> t.name()).containsExactlyInAnyOrder("echo", "propose");
    }

    private static ContentBlock.ToolUse use(String id, String tool, String text) {
        return new ContentBlock.ToolUse(id, tool, JSON.createObjectNode().put("text", text));
    }

    @Test
    void aToolCalledMoreOftenInOneRunThanItAllowsIsNotRunBeyondTheLimit() {
        var llm = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(use("t1", "metered", "a"), use("t2", "metered", "b"),
                        use("t3", "metered", "c"), use("t4", "metered", "d")), StopReason.TOOL_USE, new Usage(50, 10)))
                .thenText("Answered from two results");
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6, metered).run(ask("go", List.of()), events::add, NEVER);

        assertThat(executedWith).containsExactly("metered:a", "metered:b");
        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.toolCalls()).as("a refused call is still a call the model made").isEqualTo(4);
        var sent = llm.requests.get(1).messages();
        var results = sent.get(sent.size() - 1).content().stream().map(ContentBlock.ToolResult.class::cast).toList();
        assertThat(results).extracting(ContentBlock.ToolResult::error).containsExactly(false, false, true, true);
        assertThat(results.get(2).content()).contains("at most 2 times", "was not run");
        var shown = events.stream().filter(AgentEvent.ToolResultEvent.class::isInstance)
                .map(AgentEvent.ToolResultEvent.class::cast).toList();
        assertThat(shown).extracting(AgentEvent.ToolResultEvent::error).containsExactly(false, false, true, true);
    }

    @Test
    void theLimitCountsTheCallsOfAllTheModelRoundsOfTheRun() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "metered", "{\"text\":\"a\"}")
                .thenToolCall("t2", "metered", "{\"text\":\"b\"}")
                .thenToolCall("t3", "metered", "{\"text\":\"c\"}")
                .thenText("done");

        loop(llm, 6, metered).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(executedWith).containsExactly("metered:a", "metered:b");
    }

    @Test
    void theLimitIsPerRunAndPerToolSoAnotherQuestionOrAnotherToolIsNotAffected() {
        var firstRun = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(use("t1", "metered", "a"), use("t2", "metered", "b"),
                        use("t3", "echo", "x"), use("t4", "echo", "y")), StopReason.TOOL_USE, new Usage(50, 10)))
                .thenText("ok");
        var secondRun = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(use("t5", "metered", "c")), StopReason.TOOL_USE, new Usage(50, 10)))
                .thenText("ok");

        loop(firstRun, 6, metered).run(ask("go", List.of()), e -> { }, NEVER);
        loop(secondRun, 6, metered).run(ask("again", List.of()), e -> { }, NEVER);

        assertThat(executedWith).containsExactly("metered:a", "metered:b", "x", "y", "metered:c");
    }

    private static final Instant START = Instant.parse("2026-03-15T10:00:00Z");

    @Test
    void recordsTheModelCallOfADirectAnswerWithItsUsageAndLatency() {
        var llm = new ScriptedLlmClient().beforeEachCall(() -> clock.advance(Duration.ofMillis(250))).thenText("Hello");

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, NEVER);

        assertThat(result.llmCallRecords()).singleElement().isEqualTo(new LlmCallRecord(1, "test", 100, 20, 0, 0, 250,
                StopReason.END_TURN, LlmCallRecord.Outcome.OK, null, START));
        assertThat(result.toolCallRecords()).isEmpty();
    }

    @Test
    void recordsTheCacheTokensOfAModelCallApartFromItsInput() {
        var llm = new ScriptedLlmClient().then(new LlmResponse(List.of(new ContentBlock.Text("ok")), StopReason.END_TURN,
                new Usage(30, 12, 4000, 250)));

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, NEVER);

        assertThat(result.llmCallRecords()).singleElement().satisfies(call -> {
            assertThat(call.inputTokens()).isEqualTo(30);
            assertThat(call.outputTokens()).isEqualTo(12);
            assertThat(call.cacheReadTokens()).isEqualTo(4000);
            assertThat(call.cacheWriteTokens()).isEqualTo(250);
        });
        assertThat(result.usage()).isEqualTo(new Usage(30, 12, 4000, 250));
    }

    @Test
    void recordsEveryModelCallAndEveryToolExecutionInOrder() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(Duration.ofMillis(100)))
                .thenToolCall("t1", "echo", "{\"text\":\"ping\"}")
                .thenToolCall("t2", "echo", "{\"text\":\"pong\"}")
                .thenText("Done");
        echoTakes = Duration.ofMillis(40);

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::sequence).containsExactly(1, 2, 3);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::stopReason)
                .containsExactly(StopReason.TOOL_USE, StopReason.TOOL_USE, StopReason.END_TURN);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::startedAt)
                .containsExactly(START, START.plusMillis(140), START.plusMillis(280));
        assertThat(result.toolCallRecords()).containsExactly(
                new ToolCallRecord(1, 1, "echo", "{\"text\":\"ping\"}", "echoed", false, 40, START.plusMillis(100)),
                new ToolCallRecord(2, 2, "echo", "{\"text\":\"pong\"}", "echoed", false, 40, START.plusMillis(240)));
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void recordsWhichModelCallAskedForEachTool() {
        var llm = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(use("t1", "echo", "a"), use("t2", "echo", "b")), StopReason.TOOL_USE,
                        new Usage(50, 10)))
                .thenToolCall("t3", "echo", "{\"text\":\"c\"}")
                .thenText("Done");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.toolCallRecords()).extracting(ToolCallRecord::sequence).containsExactly(1, 2, 3);
        assertThat(result.toolCallRecords()).extracting(ToolCallRecord::llmSequence).containsExactly(1, 1, 2);
    }

    @Test
    void aToolThatRefusedItsInputIsRecordedWithTheErrorFlagAndTheMessage() {
        var llm = new ScriptedLlmClient().thenToolCall("t1", "echo", "{}").thenText("Sorry");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.toolCallRecords()).singleElement().satisfies(call -> {
            assertThat(call.error()).isTrue();
            assertThat(call.summary()).isEqualTo("Invalid input: text is required");
            assertThat(call.args()).isEqualTo("{}");
        });
    }

    @Test
    void aCallRefusedByAToolsRunLimitIsRecordedAsAnErrorToo() {
        var llm = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(use("t1", "metered", "a"), use("t2", "metered", "b"),
                        use("t3", "metered", "c")), StopReason.TOOL_USE, new Usage(50, 10)))
                .thenText("ok");

        var result = loop(llm, 6, metered).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.toolCallRecords()).extracting(ToolCallRecord::error).containsExactly(false, false, true);
        assertThat(result.toolCallRecords().get(2).summary()).contains("at most 2 times");
    }

    @Test
    void cutsWhatTheModelSentAndWhatTheToolSaidDownToTheCaps() {
        var longName = "x".repeat(400);
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", longName, "{\"text\":\"" + "a".repeat(5000) + "\"}")
                .thenText("ok");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.toolCallRecords()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).hasSize(ToolCallRecord.MAX_NAME_LENGTH);
            assertThat(call.args()).hasSize(ToolCallRecord.MAX_ARGS_LENGTH).endsWith("\u2026");
            assertThat(call.summary()).hasSize(ToolCallRecord.MAX_SUMMARY_LENGTH).startsWith("Unknown tool").endsWith("\u2026");
        });
    }

    @Test
    void aFailedModelCallIsRecordedWithItsCodeAndNoUsage() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(Duration.ofMillis(700)))
                .thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.llmCallRecords()).singleElement().isEqualTo(new LlmCallRecord(1, "test", 0, 0, 0, 0, 700, null,
                LlmCallRecord.Outcome.ERROR, ErrorCode.LLM_OVERLOADED, START));
        assertThat(result.llmCallRecords().toString()).doesNotContain("upstream-secret");
    }

    @Test
    void whatHappenedBeforeAFailureIsStillRecorded() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{\"text\":\"ping\"}")
                .thenFail(ErrorCode.LLM_ERROR, "boom");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::outcome)
                .containsExactly(LlmCallRecord.Outcome.OK, LlmCallRecord.Outcome.ERROR);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::errorCode).containsExactly(null, ErrorCode.LLM_ERROR);
        assertThat(result.toolCallRecords()).extracting(ToolCallRecord::toolName).containsExactly("echo");
        assertThat(result.usage()).isEqualTo(new Usage(50, 10));
    }

    @Test
    void aReplyCutOffByTheTokenLimitIsRecordedWithItsStopReason() {
        var llm = new ScriptedLlmClient().then(new LlmResponse(List.of(new ContentBlock.Text("The answer is cut")),
                StopReason.MAX_TOKENS, new Usage(30, 2048)));

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.TRUNCATED);
        assertThat(result.llmCallRecords()).singleElement().satisfies(call -> {
            assertThat(call.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
            assertThat(call.outcome()).isEqualTo(LlmCallRecord.Outcome.OK);
            assertThat(call.outputTokens()).isEqualTo(2048);
        });
    }

    @Test
    void aCancelledRunKeepsTheCallsThatHappenedAndNotTheOnesThatDidNot() throws Exception {
        var first = new ContentBlock.ToolUse("t1", "echo", JSON.readTree("{\"text\":\"one\"}"));
        var second = new ContentBlock.ToolUse("t2", "echo", JSON.readTree("{\"text\":\"two\"}"));
        var llm = new ScriptedLlmClient()
                .then(new LlmResponse(List.of(first, second), StopReason.TOOL_USE, new Usage(50, 10)))
                .thenText("never reached");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, () -> !executedWith.isEmpty());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(result.llmCallRecords()).singleElement().extracting(LlmCallRecord::outcome)
                .isEqualTo(LlmCallRecord.Outcome.OK);
        assertThat(result.toolCallRecords()).extracting(ToolCallRecord::args).containsExactly("{\"text\":\"one\"}");
    }

    @Test
    void aRunCancelledWhileTheModelWasAnsweringRecordsThatCallWithItsTokens() {
        var llm = new ScriptedLlmClient().thenToolCall("t1", "echo", "{\"text\":\"ping\"}").thenText("never reached");

        var result = loop(llm, 6).run(ask("go", List.of()), e -> { }, () -> !llm.requests.isEmpty());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(result.llmCallRecords()).singleElement().satisfies(call -> {
            assertThat(call.inputTokens()).isEqualTo(50);
            assertThat(call.outputTokens()).isEqualTo(10);
        });
        assertThat(result.toolCallRecords()).isEmpty();
    }

    @Test
    void aRunCancelledBeforeAnythingHappenedRecordsNothing() {
        var llm = new ScriptedLlmClient().thenText("never asked");

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, () -> true);

        assertThat(result.llmCallRecords()).isEmpty();
        assertThat(result.toolCallRecords()).isEmpty();
    }

    @Test
    void aRunThatTimedOutKeepsTheCallsMadeBeforeTheDeadline() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(Duration.ofSeconds(60)))
                .thenToolCall("t1", "echo", "{\"text\":\"one\"}")
                .thenToolCall("t2", "echo", "{\"text\":\"two\"}")
                .thenText("never reached");

        var result = loop(llm, 6).run(ask("slow question", List.of()), e -> { }, NEVER);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.RUN_TIMEOUT);
        assertThat(result.llmCallRecords()).hasSize(2).allSatisfy(call -> {
            assertThat(call.outcome()).isEqualTo(LlmCallRecord.Outcome.OK);
            assertThat(call.latencyMs()).isEqualTo(60_000);
        });
        assertThat(result.toolCallRecords()).extracting(ToolCallRecord::args).containsExactly("{\"text\":\"one\"}");
    }

    @Test
    void aModelCallCutShortByTheRunDeadlineIsRecordedWithTheRunTimeoutCode() {
        var llm = new ScriptedLlmClient()
                .beforeEachCall(() -> clock.advance(RUN_TIMEOUT.plusSeconds(1)))
                .thenFail(ErrorCode.LLM_TIMEOUT, "Claude response stream timed out");

        var result = loop(llm, 6).run(ask("slow question", List.of()), e -> { }, NEVER);

        assertThat(result.llmCallRecords()).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(LlmCallRecord.Outcome.ERROR);
            assertThat(call.errorCode()).isEqualTo(ErrorCode.RUN_TIMEOUT);
        });
    }

    @Test
    void aRunThatHitsTheIterationLimitHasOneCallRecordPerIteration() {
        var llm = new ScriptedLlmClient();
        for (int i = 0; i < 3; i++) {
            llm.thenToolCall("t" + i, "echo", "{\"text\":\"again\"}");
        }

        var result = loop(llm, 3).run(ask("loop forever", List.of()), e -> { }, NEVER);

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_ITERATIONS);
        assertThat(result.llmCallRecords()).hasSize(3);
        assertThat(result.toolCallRecords()).hasSize(3);
    }

    @Test
    void theRecordsCannotBeChangedAfterTheRun() {
        var llm = new ScriptedLlmClient().thenText("Hello");

        var result = loop(llm, 6).run(ask("hi", List.of()), e -> { }, NEVER);

        assertThat(result.llmCallRecords()).isUnmodifiable();
        assertThat(result.toolCallRecords()).isUnmodifiable();
    }
}
