package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.routing.ComplexityAssessor;
import dev.proppilot.agent.routing.ComplexityRule;
import dev.proppilot.agent.routing.ModelRouter;
import dev.proppilot.agent.routing.RoutedReason;
import dev.proppilot.agent.tools.Tool;
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
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Which model the loop sends a run to, and what it does when that model is overloaded or too slow. */
class AgentLoopRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(90);
    private static final BooleanSupplier NEVER = () -> false;
    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";
    private static final String SIMPLE = "Who is more than 30 days late on rent?";
    private static final String COMPLEX = "Which leases end in the next 60 days and require 90 days notice?";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-15T10:00:00Z"));
    private final ScriptedLlmClient llm = new ScriptedLlmClient().servingAs("anthropic", SMALL);
    private final List<AgentEvent> events = new ArrayList<>();

    private final Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "Echoes its input"; }
        public JsonNode inputSchema() { return ToolSchema.object().string("text", "text").build(); }
        public ToolResult execute(JsonNode input) {
            return ToolResult.ok(JSON, java.util.Map.of("echo", input.path("text").asText()), "echoed");
        }
    };

    private AgentLoop loop(int maxIterations, boolean largerModelConfigured) {
        var props = TestLlmProperties.defaults().model(SMALL);
        if (largerModelConfigured) {
            props.largeModel(LARGE);
        }
        var router = new ModelRouter(llm, props.build(), new ComplexityAssessor());
        var registry = new ToolRegistry(List.of(echo), mock(PlatformTransactionManager.class));
        var agentProps = new AgentProperties(maxIterations, 10, (int) RUN_TIMEOUT.toSeconds());
        return new AgentLoop(llm, registry, agentProps, clock, router, Guardrails.off());
    }

    private AgentResult ask(String question) {
        return ask(question, 6, true, List.of());
    }

    private AgentResult ask(String question, int maxIterations, boolean largerModelConfigured, List<ChatTurn> history) {
        var request = AgentRequest.question(question, history, UUID.randomUUID());
        return loop(maxIterations, largerModelConfigured).run(request, events::add, NEVER);
    }

    private List<String> modelsAsked() {
        return llm.requests.stream().map(LlmRequest::model).toList();
    }

    private List<Boolean> timeoutRetriesAllowed() {
        return llm.requests.stream().map(LlmRequest::retryTimeouts).toList();
    }

    @Test
    void aComplexQuestionIsAnsweredByTheLargerModel() {
        llm.thenText("Two leases.");

        var result = ask(COMPLEX);

        assertThat(modelsAsked()).containsExactly(LARGE);
        assertThat(result.route().model()).isEqualTo(LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.COMPLEX);
        assertThat(result.route().complexity().rules())
                .containsExactly(ComplexityRule.SEVERAL_DATA_AREAS, ComplexityRule.WINDOW_WITH_LEASE_TERM);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(LARGE);
    }

    @Test
    void aSimpleQuestionIsAnsweredByTheDefaultModel() {
        llm.thenText("Three tenants.");

        var result = ask(SIMPLE);

        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.route().complexity().score()).isZero();
    }

    @Test
    void everyCallOfARunGoesToTheModelChosenBeforeItStarted() {
        llm.thenToolCall("t1", "echo", "{\"text\":\"one\"}").thenToolCall("t2", "echo", "{\"text\":\"two\"}")
                .thenText("done");

        var result = ask(COMPLEX);

        assertThat(modelsAsked()).containsExactly(LARGE, LARGE, LARGE);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(LARGE, LARGE, LARGE);
    }

    @Test
    void withoutALargerModelEveryCallNamesTheDefaultModelAndNothingIsScored() {
        llm.thenText("Two leases.");

        var result = ask(COMPLEX, 6, false, List.of());

        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.route().complexity()).isNull();
    }

    @Test
    void theConfirmationOfADecisionIsNotScoredAndNeedsNoTools() {
        llm.thenText("The payment was recorded.");
        var notice = COMPLEX + " " + COMPLEX + " compare each of them and then summarise";

        var request = AgentRequest.confirmation(notice, List.of(), UUID.randomUUID());
        var result = loop(6, true).run(request, events::add, NEVER);

        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.route().complexity()).isNull();
    }

    @Test
    void theMessagesOfTheConversationSoFarAreScored() {
        var history = new ArrayList<ChatTurn>();
        for (int i = 0; i < 3; i++) {
            history.add(new ChatTurn("user", "question " + i));
            history.add(new ChatTurn("assistant", "answer " + i));
        }
        llm.thenText("ok");

        var result = ask("And the next one?", 6, true, history);

        assertThat(result.route().complexity().rules()).containsExactly(ComplexityRule.LONG_CONVERSATION);
    }

    @Test
    void anOverloadOfTheDefaultModelFallsBackToTheLargerOneAndAnswers() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret").thenText("Three tenants.");

        var result = ask(SIMPLE);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo("Three tenants.");
        assertThat(modelsAsked()).containsExactly(SMALL, LARGE);
        assertThat(result.route().model()).isEqualTo(LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.FALLBACK);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(events).containsExactly(new AgentEvent.Token("Three tenants."));
    }

    @Test
    void theFailedCallAndTheFallbackCallAreBothInTheTrace() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529").thenText("Three tenants.");

        var result = ask(SIMPLE);

        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::sequence).containsExactly(1, 2);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(SMALL, LARGE);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::outcome)
                .containsExactly(LlmCallRecord.Outcome.ERROR, LlmCallRecord.Outcome.OK);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::errorCode)
                .containsExactly(ErrorCode.LLM_OVERLOADED, null);
        assertThat(result.usage().inputTokens()).isEqualTo(100);
    }

    @Test
    void anOverloadOfTheLargerModelFallsBackToTheDefaultOne() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529").thenText("Two leases.");

        var result = ask(COMPLEX);

        assertThat(modelsAsked()).containsExactly(LARGE, SMALL);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.FALLBACK);
        assertThat(result.route().complexity().score()).isEqualTo(4);
    }

    @Test
    void aTimeoutFallsBackToo() {
        llm.thenFail(ErrorCode.LLM_TIMEOUT, "Claude response stream timed out").thenText("Three tenants.");

        var result = ask(SIMPLE);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(modelsAsked()).containsExactly(SMALL, LARGE);
    }

    @Test
    void anOverloadOfBothModelsFailsWithTheOriginalCodeAfterTwoCalls() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "first").thenFail(ErrorCode.LLM_OVERLOADED, "second")
                .thenText("never asked for");

        var result = ask(SIMPLE);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(result.answer()).isEqualTo(ErrorCode.LLM_OVERLOADED.message());
        assertThat(modelsAsked()).containsExactly(SMALL, LARGE);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::outcome)
                .containsExactly(LlmCallRecord.Outcome.ERROR, LlmCallRecord.Outcome.ERROR);
    }

    @Test
    void whenNoCallWasAnsweredTheRunIsReportedWhereItWasSent() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "first").thenFail(ErrorCode.LLM_OVERLOADED, "second");

        var result = ask(SIMPLE);

        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
    }

    @Test
    void aFallbackThatWasDecidedButNeverMadeIsNotTheModelOfTheRun() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText("never asked for");

        var result = loop(6, true).run(AgentRequest.question(SIMPLE, List.of(), UUID.randomUUID()), events::add,
                () -> llm.requests.size() >= 1);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ABORTED);
        assertThat(llm.requests).hasSize(1);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
    }

    @Test
    void aFallbackCallThatFailsLeavesTheRunWithTheModelThatAnsweredBefore() {
        llm.thenToolCall("t1", "echo", "{\"text\":\"one\"}")
                .thenFail(ErrorCode.LLM_OVERLOADED, "busy")
                .thenFail(ErrorCode.LLM_ERROR, "the larger model failed too");

        var result = ask(SIMPLE);

        assertThat(modelsAsked()).containsExactly(SMALL, SMALL, LARGE);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
    }

    @Test
    void aRunThatFailsAfterFallingBackFailsWithTheCodeOfTheFirstFailure() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "first").thenFail(ErrorCode.LLM_ERROR, "second");

        var result = ask(SIMPLE);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::errorCode)
                .containsExactly(ErrorCode.LLM_OVERLOADED, ErrorCode.LLM_ERROR);
        assertThat(result.route().model()).isEqualTo(SMALL);
    }

    @Test
    void noFallbackOnceTextOfTheFailedCallWasStreamed() {
        llm.thenStreamAndFail("Three tenants are ", ErrorCode.LLM_OVERLOADED, "stream cut short")
                .thenText("Three tenants are late.");

        var result = ask(SIMPLE);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(events).containsExactly(new AgentEvent.Token("Three tenants are "));
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
    }

    @Test
    void aFailureThatAnotherModelWouldNotFixIsNotRetried() {
        for (var code : List.of(ErrorCode.LLM_AUTH, ErrorCode.LLM_ERROR)) {
            llm.reset();
            llm.thenFail(code, "no").thenText("never asked for");

            var result = ask(SIMPLE);

            assertThat(result.errorCode()).isEqualTo(code);
            assertThat(llm.requests).hasSize(1);
        }
    }

    @Test
    void noFallbackWithoutASecondModel() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText("never asked for");

        var result = ask(SIMPLE, 6, false, List.of());

        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(llm.requests).hasSize(1);
        assertThat(result.llmCallRecords()).hasSize(1);
    }

    @Test
    void aRunFallsBackOnlyOnce() {
        llm.thenToolCall("t1", "echo", "{\"text\":\"one\"}")
                .thenFail(ErrorCode.LLM_OVERLOADED, "first")
                .thenToolCall("t2", "echo", "{\"text\":\"two\"}")
                .thenFail(ErrorCode.LLM_OVERLOADED, "second")
                .thenText("never asked for");

        var result = ask(SIMPLE);

        assertThat(modelsAsked()).containsExactly(SMALL, SMALL, LARGE, LARGE);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        // the larger model answered the second call, so it is the model of the run although its last call failed
        assertThat(result.route().model()).isEqualTo(LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.FALLBACK);
        assertThat(result.iterations()).isEqualTo(4);
    }

    @Test
    void theFallbackCallCountsAsAnIterationAndIsNotMadeWhenNoneIsLeft() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText("never asked for");

        var result = ask(SIMPLE, 1, true, List.of());

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void theFallbackIsMadeWhenOneIterationIsLeft() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText("Three tenants.");

        var result = ask(SIMPLE, 2, true, List.of());

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(llm.requests).hasSize(2);
    }

    @Test
    void noFallbackOnceTheRunDeadlineHasPassed() {
        llm.beforeEachCall(() -> clock.advance(RUN_TIMEOUT.plusSeconds(1)))
                .thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText("never asked for");

        var result = ask(SIMPLE);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void theClientIsToldNotToRetryATimeoutWhileTheOtherModelCanTakeTheCall() {
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText("Three tenants.");

        ask(SIMPLE);

        assertThat(timeoutRetriesAllowed()).containsExactly(false, true);
    }

    @Test
    void timeoutsAreRetriedWithoutASecondModelAndOnTheLastIteration() {
        llm.thenText("Three tenants.");
        ask(SIMPLE, 6, false, List.of());
        assertThat(timeoutRetriesAllowed()).containsExactly(true);

        llm.reset();
        llm.thenText("Three tenants.");
        ask(SIMPLE, 1, true, List.of());
        assertThat(timeoutRetriesAllowed()).containsExactly(true);
    }

    @Test
    void aTimeoutCausedByTheRunDeadlineIsARunTimeoutAndNoFallback() {
        llm.beforeEachCall(() -> clock.advance(RUN_TIMEOUT.plusSeconds(1)))
                .thenFail(ErrorCode.LLM_TIMEOUT, "Claude response stream timed out").thenText("never asked for");

        var result = ask(SIMPLE);

        assertThat(result.errorCode()).isEqualTo(ErrorCode.RUN_TIMEOUT);
        assertThat(llm.requests).hasSize(1);
    }
}
