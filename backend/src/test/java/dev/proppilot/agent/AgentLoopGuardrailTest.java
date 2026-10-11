package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.actions.ActionKind;
import dev.proppilot.actions.ActionStatus;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ComplexityAssessor;
import dev.proppilot.agent.routing.ModelRouter;
import dev.proppilot.agent.routing.RoutedReason;
import dev.proppilot.agent.tools.Tool;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.agent.tools.ToolSchema;
import dev.proppilot.config.AgentProperties;
import dev.proppilot.config.TestLlmProperties;
import dev.proppilot.guardrail.ClaimKind;
import dev.proppilot.guardrail.GroundingCorrection;
import dev.proppilot.guardrail.GuardrailMode;
import dev.proppilot.guardrail.GuardrailStatus;
import dev.proppilot.guardrail.Guardrails;
import dev.proppilot.guardrail.TenantNames;
import dev.proppilot.guardrail.Violation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the loop does with the grounding guardrail: nothing in {@code off}, a flag in {@code warn}, and in {@code retry}
 * one more call with the values named, on the larger model when there is one.
 */
class AgentLoopGuardrailTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";
    private static final String QUESTION = "What is the rent of unit A-203?";
    private static final String GROUNDED = "The rent of A-203 is SAR 4,950 a month.";
    private static final String UNGROUNDED = "The rent of A-203 is SAR 5,200 a month.";
    private static final TenantNames.Name AHMED = new TenantNames.Name("Ahmed Al-Harbi", "أحمد الحربي");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-15T10:00:00Z"));
    private final ScriptedLlmClient llm = new ScriptedLlmClient().servingAs("anthropic", SMALL);
    private final List<AgentEvent> events = new ArrayList<>();
    private final List<String> lookedUp = new ArrayList<>();
    private final List<Tool> otherTools = new ArrayList<>();
    private JsonNode sources;
    private Runnable whenLookedUp = () -> { };

    /** Returns the unit with its rent, and a lease clause source when the test gives it one. */
    private final Tool lookup = new Tool() {
        public String name() { return "lookup"; }
        public String description() { return "Looks a unit up"; }
        public JsonNode inputSchema() { return ToolSchema.object().build(); }
        public ToolResult execute(JsonNode input) {
            lookedUp.add(input.toString());
            whenLookedUp.run();
            var result = ToolResult.ok(JSON, Map.of("unit", "A-203", "monthlyRent", 4950.00,
                    "tenant", "Ahmed Al-Harbi"), "found");
            return sources == null ? result : result.withUi(sources);
        }
        @Override
        public OptionalInt maxCallsPerRun() { return OptionalInt.of(2); }
    };

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger loopLogger = (Logger) LoggerFactory.getLogger(AgentLoop.class);

    @BeforeEach
    void captureTheLog() {
        log.start();
        loopLogger.addAppender(log);
    }

    @AfterEach
    void releaseTheLog() {
        loopLogger.detachAppender(log);
    }

    private AgentLoop loop(GuardrailMode mode, int maxIterations, boolean largerModel, TenantNames.Name... tenants) {
        var props = TestLlmProperties.defaults().model(SMALL);
        if (largerModel) {
            props.largeModel(LARGE);
        }
        var router = new ModelRouter(llm, props.build(), new ComplexityAssessor());
        var registry = new ToolRegistry(toolsOfTheRun(), mock(PlatformTransactionManager.class));
        return new AgentLoop(llm, registry, new AgentProperties(maxIterations, 10, 90), clock, router,
                Guardrails.of(mode, tenants));
    }

    private List<Tool> toolsOfTheRun() {
        var all = new ArrayList<Tool>(otherTools);
        all.add(lookup);
        return all;
    }

    private AgentResult ask(GuardrailMode mode) {
        return ask(mode, 6, true);
    }

    private AgentResult ask(GuardrailMode mode, int maxIterations, boolean largerModel) {
        return run(loop(mode, maxIterations, largerModel, AHMED), AgentRequest.question(QUESTION, List.of(),
                UUID.randomUUID()));
    }

    private AgentResult run(AgentLoop loop, AgentRequest request) {
        return loop.run(request, events::add, () -> false);
    }

    /** The model reads the unit up, and then answers. */
    private void answers(String answer) {
        llm.thenToolCall("t1", "lookup", "{}").thenText(answer);
    }

    private static List<ContentBlock.Text> userTexts(LlmRequest request) {
        return request.messages().stream().filter(m -> m.role() == Message.Role.USER)
                .flatMap(m -> m.content().stream()).filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).toList();
    }

    /** A tool that returns what it is given, whatever it is called with. */
    private static Tool returning(String name, ToolResult result) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "Returns a result"; }
            public JsonNode inputSchema() { return ToolSchema.object().build(); }
            public ToolResult execute(JsonNode input) { return result; }
        };
    }

    /** What was sent from the first answer on, the answer itself excluded. */
    private List<AgentEvent> eventsAfterTheFirstAnswer() {
        var first = events.stream().filter(AgentEvent.Token.class::isInstance).findFirst().orElseThrow();
        return events.subList(events.indexOf(first) + 1, events.size());
    }

    private List<String> warnings() {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void offChecksNothingAndHasNoStatus() {
        answers(UNGROUNDED);

        var result = ask(GuardrailMode.OFF);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.guardrail()).isNull();
    }

    @Test
    void warnFindsAGroundedAnswerOk() {
        answers(GROUNDED);

        var result = ask(GuardrailMode.WARN);

        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.OK);
        assertThat(result.guardrail().violations()).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void warnFlagsAnUngroundedAnswerAndDeliversItAsItIsWithoutAnotherCall() {
        answers(UNGROUNDED);

        var result = ask(GuardrailMode.WARN);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(result.guardrail().violations())
                .containsExactly(new Violation(ClaimKind.NUMBER, "5,200", "5200"));
        assertThat(llm.requests).hasSize(2);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(events).noneMatch(AgentEvent.Revise.class::isInstance);
    }

    @Test
    void warnWritesOneWarningWithTheRunTheKindsAndTheValuesButNeverTheAnswerOrTheQuestion() {
        answers("Ahmed Al-Harbi pays SAR 5,200 for A-204, see [LSE-A-204#4].");
        var runId = UUID.randomUUID();

        run(loop(GuardrailMode.WARN, 6, true, AHMED), AgentRequest.question(QUESTION, List.of(), runId));

        assertThat(warnings()).hasSize(1);
        var line = warnings().get(0);
        assertThat(line).contains(runId.toString(), "NUMBER \"5,200\"", "UNIT_CODE \"A-204\"",
                "CITATION \"LSE-A-204#4\"", "3 value(s)");
        assertThat(line).doesNotContain("pays").doesNotContain("What is the rent");
    }

    @Test
    void theValuesInTheWarningAreCutAndFreeOfControlCharacters() {
        answers("See [LSE-" + "A".repeat(50) + "] and then more.");

        ask(GuardrailMode.WARN);

        var line = warnings().get(0);
        assertThat(line).contains("…\"").doesNotContain("A".repeat(37));
    }

    @Test
    void theNameOfATenantThatNothingStatesIsFlagged() {
        llm.thenText("Ahmed Al-Harbi is the tenant.");

        var result = run(loop(GuardrailMode.WARN, 6, true, AHMED), AgentRequest.question("Who rents A-203?", List.of(),
                UUID.randomUUID()));

        assertThat(result.guardrail().violations()).extracting(Violation::kind).containsExactly(ClaimKind.TENANT_NAME);
    }

    @Test
    void whatTheToolsReturnedGroundsTheAnswer() {
        answers("Ahmed Al-Harbi pays SAR 4,950 for A-203.");

        var result = ask(GuardrailMode.WARN);

        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.OK);
    }

    @Test
    void aValueOnlyTheModelsOwnQueryStatesIsNotGroundedByTheResultThatEchoesIt() {
        var echoed = Map.of("query", "late fee 150 SAR", "results", List.of());
        otherTools.add(returning("search", ToolResult.ok(JSON, echoed, "No clause")
                .withEvidence("{\"results\":[]}")));
        llm.thenToolCall("t1", "search", "{}").thenText("The late fee is 150 SAR a day.");

        var result = run(loop(GuardrailMode.WARN, 6, true), AgentRequest.question("What is the late fee?", List.of(),
                UUID.randomUUID()));

        assertThat(result.guardrail().violations()).containsExactly(new Violation(ClaimKind.NUMBER, "150", "150"));
    }

    @Test
    void theTextOfARejectedCallGroundsNeitherTheCodeItQuotesNorTheOneItGivesAsAnExample() {
        otherTools.add(returning("get_unit", ToolResult.rejected("No unit with code 'B-777'. Codes look like A-203 "
                + "(building letter A-F, then floor and number).")));
        llm.thenToolCall("t1", "get_unit", "{}").thenText("There is no unit B-777, and A-203 is only an example.");

        var result = run(loop(GuardrailMode.WARN, 6, true), AgentRequest.question("Who rents the penthouse?",
                List.of(), UUID.randomUUID()));

        assertThat(result.guardrail().violations()).extracting(Violation::value).containsExactly("B-777", "A-203");
    }

    @Test
    void anErrorThatNamesTenantsAndUnitsStillGroundsTheQuestionThatAsksWhichOne() {
        otherTools.add(returning("remind", ToolResult.error("2 tenants match 'Ahmed'. Ask the user which one. First "
                + "matches: Ahmed Al-Harbi (unit B-203), Ahmed Al-Otaibi (unit A-203)")));
        llm.thenToolCall("t1", "remind", "{}").thenText("Do you mean Ahmed Al-Harbi in B-203 or the tenant of A-203?");

        var result = run(loop(GuardrailMode.WARN, 6, true, AHMED), AgentRequest.question("Remind Ahmed", List.of(),
                UUID.randomUUID()));

        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.OK);
    }

    @Test
    void theQuestionAndTheConversationGroundTheAnswerAndSoDoesTodaysDate() {
        llm.thenText("Earlier you saw 7,200; you ask about 9,999 on 2026-03-15.");
        var history = List.of(new ChatTurn("user", "What was the rent?"),
                new ChatTurn("assistant", "The rent was 7,200 SAR."));
        var request = AgentRequest.question("Is 9,999 right?", history, UUID.randomUUID());

        var result = run(loop(GuardrailMode.WARN, 6, true), request);

        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.OK);
    }

    @Test
    void theRefsOfALeaseSearchGroundACitationAndAMadeUpOneIsFlagged() {
        sources = JSON.createObjectNode().put("type", ToolResult.SOURCES_UI_TYPE).set("sources",
                JSON.createArrayNode().add(JSON.createObjectNode().put("ref", "LSE-A-203#5")));
        answers("The rent is due monthly [LSE-A-203#5], and rises yearly [LSE-A-203#9].");

        var result = ask(GuardrailMode.WARN);

        assertThat(result.guardrail().violations())
                .containsExactly(new Violation(ClaimKind.CITATION, "LSE-A-203#9", "LSE-A-203#9"));
    }

    @Test
    void theWordsTheClientWritesIntoItsAnswersAreTheServersOwnAndGroundTheirFigures() {
        var offline = new ScriptedLlmClient() {
            @Override
            public List<String> ownText() {
                return List.of("Try \"Record a payment of 2000 for unit A-203\".");
            }
        }.servingAs("offline", "rule-based");
        offline.thenText("Try: record a payment of 2000 for unit A-203.");
        var router = new ModelRouter(offline, TestLlmProperties.defaults().build(), new ComplexityAssessor());
        var registry = new ToolRegistry(List.of(lookup), mock(PlatformTransactionManager.class));
        var loop = new AgentLoop(offline, registry, new AgentProperties(6, 10, 90), clock, router,
                Guardrails.of(GuardrailMode.WARN));

        var result = run(loop, AgentRequest.question("hello", List.of(), UUID.randomUUID()));

        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.OK);
    }

    @Test
    void aTruncatedAnswerIsNotChecked() {
        llm.then(new LlmResponse(List.of(new ContentBlock.Text("The rent is 5,200")), StopReason.MAX_TOKENS,
                new Usage(10, 2048)));

        var result = ask(GuardrailMode.WARN);

        assertThat(result.status()).isEqualTo(AgentResult.Status.TRUNCATED);
        assertThat(result.guardrail()).isNull();
    }

    @Test
    void aRunThatFailedIsNotChecked() {
        llm.thenFail(ErrorCode.LLM_ERROR, "boom");

        var result = ask(GuardrailMode.WARN, 6, false);

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.guardrail()).isNull();
    }

    @Test
    void aCheckThatFailsLeavesTheAnswerAsItWasAndTheRunUnchecked() {
        var throwing = new TenantNames(() -> {
            throw new IllegalStateException("tenants unreadable: Ahmed Al-Harbi");
        });
        var router = new ModelRouter(llm, TestLlmProperties.defaults().model(SMALL).build(), new ComplexityAssessor());
        var registry = new ToolRegistry(List.of(lookup), mock(PlatformTransactionManager.class));
        var guardrail = new dev.proppilot.guardrail.Guardrail(GuardrailMode.RETRY,
                new dev.proppilot.guardrail.GroundingChecker(throwing));
        var loop = new AgentLoop(llm, registry, new AgentProperties(6, 10, 90), clock, router, guardrail);
        llm.thenText("Ahmed Al-Harbi pays 5,200.");

        var result = run(loop, AgentRequest.question(QUESTION, List.of(), UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo("Ahmed Al-Harbi pays 5,200.");
        assertThat(result.guardrail()).isNull();
        assertThat(llm.requests).hasSize(1);
        assertThat(log.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains("Ahmed"));
    }

    @Test
    void aConfirmationIsCheckedAgainstTheStoredSummaryAndResultTheServerWroteIntoTheNotice() {
        var result = JSON.createObjectNode().put("applied", 4950.00).put("outstandingAfter", 1250.50)
                .put("unitCode", "A-203");
        var notice = new DecisionNotice(ActionStatus.APPROVED, ActionKind.RECORD_PAYMENT,
                "Record payment of SAR 4,950.00 for unit A-203 (Ahmed Al-Harbi)",
                "تسجيل دفعة بمبلغ 4,950.00 ريال للوحدة A-203 (أحمد الحربي)", null, result).text();
        llm.thenText("Approved: SAR 4,950 was applied to A-203 for Ahmed Al-Harbi, and SAR 1,250.50 is still due.");
        llm.thenText("Approved: SAR 4,950 was applied to A-203, and SAR 7,000 is still due.");

        var grounded = run(loop(GuardrailMode.WARN, 6, true, AHMED),
                AgentRequest.confirmation(notice, List.of(), UUID.randomUUID()));
        var flagged = run(loop(GuardrailMode.WARN, 6, true, AHMED),
                AgentRequest.confirmation(notice, List.of(), UUID.randomUUID()));

        assertThat(grounded.guardrail().status()).isEqualTo(GuardrailStatus.OK);
        assertThat(flagged.guardrail().violations()).extracting(Violation::value).containsExactly("7,000");
        assertThat(llm.requests).extracting(LlmRequest::tools).allMatch(List::isEmpty);
    }

    @Test
    void retryAsksAgainOnTheLargerModelWithTheValuesNamedAndTellsTheClientToDropTheFirstAnswer() {
        answers(UNGROUNDED);
        llm.thenText(GROUNDED);

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo(GROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.RETRIED_OK);
        assertThat(result.guardrail().violations()).isEmpty();
        assertThat(llm.requests).extracting(LlmRequest::model).containsExactly(SMALL, SMALL, LARGE);
        assertThat(result.route().model()).isEqualTo(LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.GUARDRAIL_RETRY);
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResultEvent", "Token", "Revise", "Token");
        assertThat(events.get(3)).isEqualTo(new AgentEvent.Revise("grounding"));
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(SMALL, SMALL, LARGE);
        assertThat(result.usage().inputTokens()).isEqualTo(50 + 100 + 100);
    }

    @Test
    void theRetryIsSentTheFirstAnswerAndAServerWrittenTurnThatListsTheValues() {
        answers(UNGROUNDED);
        llm.thenText(GROUNDED);

        ask(GuardrailMode.RETRY);

        var messages = llm.requests.get(2).messages();
        var last = messages.get(messages.size() - 1);
        var beforeLast = messages.get(messages.size() - 2);
        assertThat(beforeLast.role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(beforeLast.content()).containsExactly(new ContentBlock.Text(UNGROUNDED));
        assertThat(last.role()).isEqualTo(Message.Role.USER);
        var correction = ((ContentBlock.Text) last.content().get(0)).text();
        assertThat(correction).startsWith(GroundingCorrection.MARKER)
                .contains("NUMBER \"5,200\"", "not by the user", "using only values that you find in them",
                        "say so plainly", "language of your previous answer");
        assertThat(correction).doesNotContain("A-203");
    }

    @Test
    void theRetryStaysOnTheModelItIsOnWhenThereIsNoLargerOne() {
        answers(UNGROUNDED);
        llm.thenText(GROUNDED);

        var result = ask(GuardrailMode.RETRY, 6, false);

        assertThat(llm.requests).extracting(LlmRequest::model).containsExactly(SMALL, SMALL, SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.RETRIED_OK);
    }

    @Test
    void anAnswerThatIsStillUngroundedIsFlaggedAfterTheOneRetryAndNotAskedForAgain() {
        answers(UNGROUNDED);
        llm.thenText("The rent of A-203 is SAR 5,300 a month.").thenText("never asked for");

        var result = ask(GuardrailMode.RETRY);

        assertThat(llm.requests).hasSize(3);
        assertThat(result.answer()).isEqualTo("The rent of A-203 is SAR 5,300 a month.");
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.RETRIED_FLAGGED);
        assertThat(result.guardrail().violations()).extracting(Violation::value).containsExactly("5,300");
        assertThat(warnings()).hasSize(1).allMatch(line -> line.contains("5,300") && !line.contains("5,200"));
    }

    @Test
    void theValuesTheCorrectionNamesAreNotEvidenceForTheAnswerThatFollowsIt() {
        answers(UNGROUNDED);
        llm.thenText(UNGROUNDED);

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.RETRIED_FLAGGED);
    }

    @Test
    void aGroundedAnswerIsNeverAskedForAgain() {
        answers(GROUNDED);

        var result = ask(GuardrailMode.RETRY);

        assertThat(llm.requests).hasSize(2);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.OK);
        assertThat(events).noneMatch(AgentEvent.Revise.class::isInstance);
    }

    @Test
    void theRetryMayCallToolsAgainWithinTheLimitsOfTheRun() {
        answers(UNGROUNDED);
        llm.thenToolCall("t2", "lookup", "{}").thenToolCall("t3", "lookup", "{}").thenText(GROUNDED);

        var result = ask(GuardrailMode.RETRY, 8, true);

        // the tool allows two calls a run: the first of the run and one of the retry; the third is refused
        assertThat(lookedUp).hasSize(2);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.RETRIED_OK);
        assertThat(events).filteredOn(AgentEvent.Revise.class::isInstance).hasSize(1);
        // the client is told to drop the first answer before the retry's own tool call is shown
        var revise = events.indexOf(new AgentEvent.Revise("grounding"));
        assertThat(events.get(revise + 1)).isInstanceOf(AgentEvent.ToolCall.class);
    }

    @Test
    void noRetryWhenTheRunHasNoIterationLeft() {
        answers(UNGROUNDED);

        var result = ask(GuardrailMode.RETRY, 2, true);

        assertThat(llm.requests).hasSize(2);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(events).noneMatch(AgentEvent.Revise.class::isInstance);
    }

    @Test
    void noRetryOnceTheRunDeadlineHasPassed() {
        llm.beforeEachCall(() -> clock.advance(Duration.ofSeconds(100)));
        llm.thenText(UNGROUNDED).thenText(GROUNDED);

        var result = ask(GuardrailMode.RETRY);

        assertThat(llm.requests).hasSize(1);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
    }

    @Test
    void aRetryThatFailsBeforeItStreamedLeavesTheFirstAnswerStandingFlagged() {
        answers(UNGROUNDED);
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret")
                .thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.errorCode()).isNull();
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(events).noneMatch(AgentEvent.Revise.class::isInstance);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::outcome).containsExactly(
                LlmCallRecord.Outcome.OK, LlmCallRecord.Outcome.OK, LlmCallRecord.Outcome.ERROR,
                LlmCallRecord.Outcome.ERROR);
        assertThat(result.route().model()).isEqualTo(SMALL);
    }

    @Test
    void aRetryThatFailsAfterItStreamedLeavesTheFirstAnswerStandingAndSendsItAgain() {
        answers(UNGROUNDED);
        llm.thenStreamAndFail("The rent is ", ErrorCode.LLM_ERROR, "cut short");

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.errorCode()).isNull();
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::outcome).containsExactly(
                LlmCallRecord.Outcome.OK, LlmCallRecord.Outcome.OK, LlmCallRecord.Outcome.ERROR);
        // what the client holds after these: the first answer, dropped, a part of the new one, dropped, the first again
        assertThat(eventsAfterTheFirstAnswer()).containsExactly(new AgentEvent.Revise("grounding"),
                new AgentEvent.Token("The rent is "), new AgentEvent.Revise("grounding"),
                new AgentEvent.Token(UNGROUNDED));
    }

    @Test
    void aRetryThatAsksForToolsUntilTheRunHasNoIterationLeftLeavesTheFirstAnswerStandingFlagged() {
        answers(UNGROUNDED);
        llm.thenToolCall("t2", "lookup", "{}");

        var result = ask(GuardrailMode.RETRY, 3, true);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(result.guardrail().violations()).extracting(Violation::value).containsExactly("5,200");
        assertThat(llm.requests).extracting(LlmRequest::model).containsExactly(SMALL, SMALL, LARGE);
        assertThat(eventsAfterTheFirstAnswer()).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("Revise", "ToolCall", "ToolResultEvent", "Revise", "Token");
        assertThat(events).noneMatch(e -> e instanceof AgentEvent.Token token && token.text().equals(AgentLoop.GAVE_UP));
        // the model that wrote the answer that was delivered answered, not the one whose tools were run for nothing
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(SMALL, SMALL, LARGE);
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void aRetryThatRunsOutOfTimeBetweenItsToolCallsLeavesTheFirstAnswerStandingFlagged() {
        answers(UNGROUNDED);
        llm.thenToolCall("t2", "lookup", "{}");
        whenLookedUp = () -> {
            if (lookedUp.size() == 2) {
                clock.advance(Duration.ofSeconds(100));
            }
        };

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.errorCode()).isNull();
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(events.get(events.size() - 1)).isEqualTo(new AgentEvent.Token(UNGROUNDED));
    }

    @Test
    void aRetryThatAnswersNothingAfterItsToolsLeavesTheFirstAnswerStandingFlagged() {
        answers(UNGROUNDED);
        llm.thenToolCall("t2", "lookup", "{}").thenText("");

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(events.get(events.size() - 1)).isEqualTo(new AgentEvent.Token(UNGROUNDED));
        assertThat(events.get(events.size() - 2)).isEqualTo(new AgentEvent.Revise("grounding"));
    }

    @Test
    void aRetryCutOffBeforeItWroteAnythingLeavesTheFirstAnswerStandingFlagged() {
        answers(UNGROUNDED);
        llm.then(new LlmResponse(List.of(), StopReason.MAX_TOKENS, new Usage(10, 2048)));

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(events).noneMatch(AgentEvent.Revise.class::isInstance);
    }

    @Test
    void aRunThatNeverRetriedStillEndsWithTheStepLimitAnswerWhenItHasNoIterationLeft() {
        llm.thenToolCall("t1", "lookup", "{}").thenToolCall("t2", "lookup", "{}");

        var result = ask(GuardrailMode.RETRY, 2, true);

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_ITERATIONS);
        assertThat(result.answer()).isEqualTo(AgentLoop.GAVE_UP);
        assertThat(result.guardrail()).isNull();
    }

    @Test
    void anEmptyRetryAnswerLeavesTheFirstAnswerStandingFlagged() {
        answers(UNGROUNDED);
        llm.thenText("");

        var result = ask(GuardrailMode.RETRY);

        assertThat(result.answer()).isEqualTo(UNGROUNDED);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.FLAGGED);
        assertThat(events).noneMatch(AgentEvent.Revise.class::isInstance);
    }

    @Test
    void aRetryThatFallsBackToTheOtherModelStillHasItsAnswerChecked() {
        answers(UNGROUNDED);
        llm.thenFail(ErrorCode.LLM_OVERLOADED, "busy").thenText(GROUNDED);

        var result = ask(GuardrailMode.RETRY);

        assertThat(llm.requests).extracting(LlmRequest::model).containsExactly(SMALL, SMALL, LARGE, SMALL);
        assertThat(result.guardrail().status()).isEqualTo(GuardrailStatus.RETRIED_OK);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.FALLBACK);
    }

    @Test
    void aQuestionAlreadyOnTheLargerModelIsAskedAgainThereAndSaysWhy() {
        var complex = "Which leases end in the next 60 days and require 90 days notice?";
        llm.thenText("Seven leases: 4,444.").thenText("Seven leases.");

        var result = run(loop(GuardrailMode.RETRY, 6, true), AgentRequest.question(complex, List.of(),
                UUID.randomUUID()));

        assertThat(llm.requests).extracting(LlmRequest::model).containsExactly(LARGE, LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.GUARDRAIL_RETRY);
        assertThat(result.route().complexity().score()).isEqualTo(4);
    }

    @Test
    void theRetryDoesNotChangeWhatTheModelWasToldBeforeIt() {
        answers(UNGROUNDED);
        llm.thenText(GROUNDED);

        ask(GuardrailMode.RETRY);

        var first = userTexts(llm.requests.get(0));
        assertThat(first).containsExactly(new ContentBlock.Text(QUESTION));
        assertThat(llm.requests.get(2).system()).isEqualTo(llm.requests.get(0).system());
        assertThat(llm.requests.get(2).tools()).isEqualTo(llm.requests.get(0).tools());
    }
}
