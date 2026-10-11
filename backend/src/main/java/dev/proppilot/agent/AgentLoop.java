package dev.proppilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.ToolSpec;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ModelRoute;
import dev.proppilot.agent.routing.ModelRouter;
import dev.proppilot.agent.tools.ToolContext;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.config.AgentProperties;
import dev.proppilot.guardrail.Evidence;
import dev.proppilot.guardrail.GroundingCorrection;
import dev.proppilot.guardrail.Guardrail;
import dev.proppilot.guardrail.GuardrailMode;
import dev.proppilot.guardrail.GuardrailOutcome;
import dev.proppilot.guardrail.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The agent loop, written by hand: send messages and tool definitions to the model; if it asks for tools, run them,
 * append the results and ask again; stop on a final answer, a reply cut off by the token limit, the iteration limit or
 * the run deadline. Every model call and tool execution is measured with the injected clock and returned in the
 * result, whatever became of the run.
 *
 * <p>The router picks the model of the run before it starts. A call that fails because that model is overloaded or too
 * slow is made again, once per run, on the other model, when there is one and the call had streamed nothing. While that
 * is still possible, the client is told not to retry a call that timed out on the same model, which would use the
 * time the other model needs. The result names the model of the last call that was answered, not the one the loop was
 * about to try.
 *
 * <p>The grounding guardrail checks the final answer. In {@code retry} mode an answer that states values nothing in the
 * run supports is asked for once more, on the larger model when there is one, with the values named; the client is
 * told to drop the earlier answer when the new one begins. If that second call gives no answer of its own (it fails,
 * it is empty, or it asks for tools until the run is out of iterations or time) the earlier answer stands, flagged,
 * and is sent to the client again, and the result names the model that wrote it: the guardrail never turns an answer
 * into an error.
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    static final String GAVE_UP = "I could not finish this request within my step limit. Please try a narrower question.";

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentProperties props;
    private final Clock clock;
    private final ModelRouter router;
    private final Guardrail guardrail;

    public AgentLoop(LlmClient llm, ToolRegistry tools, AgentProperties props, Clock clock, ModelRouter router,
                     Guardrail guardrail) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.clock = clock;
        this.router = router;
        this.guardrail = guardrail;
    }

    /**
     * Answers one turn. The run ends with error code {@code run_timeout} once the run deadline has passed, and
     * each model call is given only the time that is left.
     *
     * @param cancelled polled before every model call and tool execution and after every model reply; once it is
     *                  true the run stops and returns what it has spent so far with status {@code ABORTED}
     */
    public AgentResult run(AgentRequest request, Consumer<AgentEvent> events, BooleanSupplier cancelled) {
        return new Run(request, events, cancelled).execute();
    }

    /** The state of one question, so the loop reads as a sequence of small steps. */
    private final class Run {

        private final UUID runId;
        private final Consumer<AgentEvent> events;
        private final BooleanSupplier cancelled;
        private final List<Message> messages = new ArrayList<>();
        private final String system = SystemPrompt.forDate(LocalDate.now(clock));
        private final boolean toolsOffered;
        private final List<ToolSpec> toolSpecs;
        private final ToolContext context;
        private final Instant deadline = clock.instant().plus(props.runTimeout());
        private final Map<String, Integer> callsByTool = new HashMap<>();
        private final List<LlmCallRecord> llmCalls = new ArrayList<>();
        private final List<ToolCallRecord> toolCallRecords = new ArrayList<>();
        /** What the guardrail holds an answer against: what the model was given, and what its tools stated. */
        private final List<String> givenTexts;
        private final List<String> toolResultTexts = new ArrayList<>();
        private final Set<String> retrievedRefs = new LinkedHashSet<>();
        private Usage usage = Usage.ZERO;
        private int iterations;
        /** Where the next call goes. */
        private ModelRoute route;
        /**
         * Where the last call that was answered went, or where the run was sent while none was: what the result
         * reports. Where the answer that is delivered came from, when the answer asked for again did not come.
         */
        private ModelRoute answeredBy;
        /** The code of the failure that sent the run to the other model. Set once: a run falls back only once. */
        private ErrorCode fellBackFrom;
        /** Set once the guardrail has asked for the answer again: a run is asked only once. */
        private Retry retry;

        Run(AgentRequest request, Consumer<AgentEvent> events, BooleanSupplier cancelled) {
            this.runId = request.runId();
            this.events = events;
            this.cancelled = cancelled;
            this.toolsOffered = request.toolsOffered();
            this.toolSpecs = toolsOffered ? tools.specs() : List.of();
            this.context = new ToolContext(runId);
            messages.addAll(conversationSoFar(request.history()));
            // A confirmation only words an outcome that is settled, so it has no question to score.
            this.route = toolsOffered ? router.route(request.message(), messages.size()) : router.defaultRoute();
            this.answeredBy = route;
            messages.add(Message.user(request.message()));
            this.givenTexts = givenTexts(messages);
        }

        /**
         * The question and the conversation, today's date (which the system prompt states) and the words the client
         * itself writes into its answers: the text of the turn that the app and the person wrote.
         */
        private List<String> givenTexts(List<Message> conversation) {
            var texts = new ArrayList<String>();
            for (var message : conversation) {
                for (var block : message.content()) {
                    if (block instanceof ContentBlock.Text text) {
                        texts.add(text.text());
                    }
                }
            }
            texts.add(LocalDate.now(clock).toString());
            texts.addAll(llm.ownText());
            return texts;
        }

        AgentResult execute() {
            while (iterations < props.maxIterations()) {
                var finished = step();
                if (finished.isPresent()) {
                    return finished.get();
                }
            }
            if (retry != null && !cancelled.getAsBoolean()) {
                // The tools of the retry used the last iteration
                return keepEarlierAnswer();
            }
            events.accept(new AgentEvent.Token(GAVE_UP));
            return cancelled.getAsBoolean() ? aborted() : result(AgentResult.Status.MAX_ITERATIONS, GAVE_UP);
        }

        /** One model call and the tools it asked for. Returns the final result once the run is over. */
        private Optional<AgentResult> step() {
            var interruption = interruption();
            if (interruption.isPresent()) {
                return interruption;
            }
            iterations++;
            var startedAt = clock.instant();
            var streamedText = new AtomicBoolean();
            // A model that timed out is not asked again when the other one can take the call: that would use its time.
            boolean retryTimeouts = fallbackRoute().isEmpty();
            LlmResponse response;
            try {
                response = llm.complete(
                        new LlmRequest(system, List.copyOf(messages), toolSpecs, timeLeft(), route.model(),
                                retryTimeouts),
                        text -> {
                            if (!text.isEmpty()) {
                                streamedText.set(true);
                                reviseEarlierAnswer();
                            }
                            events.accept(new AgentEvent.Token(text));
                        });
            } catch (LlmException e) {
                return afterFailedCall(e, startedAt, streamedText.get());
            }
            llmCalls.add(LlmCallRecord.succeeded(llmCalls.size() + 1, route.model(), startedAt, millisSince(startedAt),
                    response));
            answeredBy = route;
            usage = usage.plus(response.usage());
            if (cancelled.getAsBoolean()) {
                // The client left while the reply was streaming, so nobody received it.
                return Optional.of(aborted());
            }
            if (response.stopReason() == StopReason.MAX_TOKENS) {
                // Tool calls in a reply that hit the token limit may be incomplete, so none are run.
                if (retry != null && response.text().isBlank()) {
                    return Optional.of(keepEarlierAnswer());
                }
                return Optional.of(result(AgentResult.Status.TRUNCATED, response.text()));
            }
            messages.add(new Message(Message.Role.ASSISTANT, response.content()));

            var requested = response.toolUses();
            if (requested.isEmpty()) {
                return afterAnswer(response.text());
            }
            return runTools(requested);
        }

        /**
         * The model gave its final answer. The guardrail checks it, as far as its mode goes: the answer is delivered
         * with what was found, or, in {@code retry} mode, the model is asked for it again, once.
         */
        private Optional<AgentResult> afterAnswer(String answer) {
            if (retry != null && answer.isBlank()) {
                return Optional.of(keepEarlierAnswer());
            }
            var checked = guardrail.check(answer, evidence());
            if (checked.isEmpty()) {
                return Optional.of(result(AgentResult.Status.OK, answer));
            }
            var violations = checked.get();
            if (!violations.isEmpty() && canAskAgain()) {
                askAgain(answer, violations);
                return Optional.empty();
            }
            return Optional.of(delivered(answer, violations, retry != null));
        }

        private Evidence evidence() {
            return new Evidence(toolResultTexts, givenTexts, retrievedRefs);
        }

        private boolean canAskAgain() {
            return guardrail.mode() == GuardrailMode.RETRY && retry == null && iterations < props.maxIterations()
                    && timeLeft().isPositive() && !cancelled.getAsBoolean();
        }

        /**
         * Appends the server's own turn that names the values and sends the run to the model that answers again. The
         * client is told to drop the earlier answer only when the new one begins, so that a call that fails first
         * leaves it an answer to show.
         */
        private void askAgain(String answer, List<Violation> violations) {
            log.info("Grounding guardrail asks run {} to answer again: {} value(s) not found in the data ({})", runId,
                    violations.size(), Violation.describe(violations));
            retry = new Retry(answer, violations, answeredBy);
            route = router.retryFrom(route);
            messages.add(Message.user(GroundingCorrection.message(violations)));
        }

        private void reviseEarlierAnswer() {
            if (retry != null && !retry.revised) {
                retry.revised = true;
                events.accept(new AgentEvent.Revise(AgentEvent.Revise.GROUNDING));
            }
        }

        /** The answer of a run that was checked, with what the guardrail found; one warning for a flagged one. */
        private AgentResult delivered(String answer, List<Violation> violations, boolean retried) {
            var outcome = GuardrailOutcome.of(violations, retried);
            if (outcome.flagged()) {
                log.warn("Grounding guardrail flagged run {}: {} value(s) not found in the data ({})", runId,
                        violations.size(), Violation.describe(violations));
            }
            return result(AgentResult.Status.OK, answer, outcome);
        }

        /**
         * The answer asked for again never came: the first one stands, flagged, and the model that wrote it is the one
         * that answered. A client that was told to drop it, and may have been sent a part of the new one, is told to
         * drop that and is sent the first one again.
         */
        private AgentResult keepEarlierAnswer() {
            if (retry.revised) {
                events.accept(new AgentEvent.Revise(AgentEvent.Revise.GROUNDING));
                events.accept(new AgentEvent.Token(retry.answer));
            }
            answeredBy = retry.answeredBy;
            return delivered(retry.answer, retry.violations, false);
        }

        /**
         * The model call failed. That ends the run, unless the call can be made again on the other model: only for an
         * overload or a timeout, only once, only while the run has iterations and time left, and only when none of its
         * text reached the client, which would otherwise read the answer twice.
         */
        private Optional<AgentResult> afterFailedCall(LlmException failure, Instant startedAt, boolean streamedText) {
            log.warn("LLM call failed on iteration {} ({}): {}", iterations, failure.code(), failure.getMessage());
            var code = failureCode(failure);
            llmCalls.add(LlmCallRecord.failed(llmCalls.size() + 1, route.model(), startedAt, millisSince(startedAt),
                    code));
            var fallback = fallbackAfter(code, streamedText);
            if (fallback.isEmpty()) {
                // The fallback only ever turns a failure into an answer: a run that fails after it fails as it would
                // have without it, with the code of the first failure.
                return Optional.of(failed(fellBackFrom != null ? fellBackFrom : code));
            }
            log.warn("Falling back from {} to {} after {}", route.model(), fallback.get().model(), code.code());
            fellBackFrom = code;
            route = fallback.get();
            return Optional.empty();
        }

        private Optional<ModelRoute> fallbackAfter(ErrorCode code, boolean streamedText) {
            boolean transientFailure = code == ErrorCode.LLM_OVERLOADED || code == ErrorCode.LLM_TIMEOUT;
            if (!transientFailure || streamedText || !timeLeft().isPositive()) {
                return Optional.empty();
            }
            return fallbackRoute();
        }

        /** Where a call that fails now would be made again: the run has not fallen back yet and has an iteration left. */
        private Optional<ModelRoute> fallbackRoute() {
            if (fellBackFrom != null || iterations >= props.maxIterations()) {
                return Optional.empty();
            }
            return router.fallbackFrom(route);
        }

        /** Runs the tools and queues their results for the next model call; a result is returned only if interrupted. */
        private Optional<AgentResult> runTools(List<ContentBlock.ToolUse> requested) {
            var results = new ArrayList<ContentBlock>();
            for (var call : requested) {
                var interruption = interruption();
                if (interruption.isPresent()) {
                    return interruption;
                }
                reviseEarlierAnswer();
                events.accept(new AgentEvent.ToolCall(call.name(), call.input()));
                var startedAt = clock.instant();
                var result = execute(call);
                toolResultTexts.add(result.evidence());
                rememberRefs(result.ui());
                // The call that asked for these tools is the one that was answered last.
                toolCallRecords.add(new ToolCallRecord(toolCallRecords.size() + 1, llmCalls.size(), call.name(),
                        call.input().toString(), result.summary(), result.error(), millisSince(startedAt), startedAt));
                events.accept(new AgentEvent.ToolResultEvent(call.name(), result.summary(), result.error(), result.ui()));
                results.add(new ContentBlock.ToolResult(call.id(), result.content(), result.error()));
            }
            messages.add(new Message(Message.Role.USER, results));
            return Optional.empty();
        }

        /** The refs of the clauses a lease search returned: the only ones an answer may cite. */
        private void rememberRefs(JsonNode ui) {
            if (ui != null && ToolResult.SOURCES_UI_TYPE.equals(ui.path("type").asText())) {
                ui.path("sources").forEach(source -> retrievedRefs.add(source.path("ref").asText()));
            }
        }

        /**
         * A run that offered no tools runs none, even if the model asks for one. A tool that limits its calls per run
         * is not run beyond that: the call is answered with an error that tells the model to work with what it has.
         */
        private ToolResult execute(ContentBlock.ToolUse call) {
            if (!toolsOffered) {
                return ToolResult.rejected("No tools are available for this reply.");
            }
            int calls = callsByTool.merge(call.name(), 1, Integer::sum);
            var limit = tools.maxCallsPerRun(call.name());
            if (limit.isPresent() && calls > limit.getAsInt()) {
                log.warn("Tool {} was called {} times in one run, over its limit of {}", call.name(), calls,
                        limit.getAsInt());
                return ToolResult.rejected("This reply may call " + call.name() + " at most " + limit.getAsInt()
                        + " times and this call was not run. Answer from the results you already have, and say plainly "
                        + "what was left out.");
            }
            return tools.execute(call.name(), call.input(), context);
        }

        /** Checked before every model call and tool execution: the client left, or the run is out of time. */
        private Optional<AgentResult> interruption() {
            if (cancelled.getAsBoolean()) {
                return Optional.of(aborted());
            }
            if (!timeLeft().isPositive()) {
                log.warn("Run stopped after {} model calls: it exceeded {}", iterations, props.runTimeout());
                return Optional.of(failed(ErrorCode.RUN_TIMEOUT));
            }
            return Optional.empty();
        }

        /** A model call that timed out once the run deadline had passed was cut short by it: the run timed out. */
        private ErrorCode failureCode(LlmException failure) {
            boolean outOfTime = failure.code() == ErrorCode.LLM_TIMEOUT && !timeLeft().isPositive();
            return outOfTime ? ErrorCode.RUN_TIMEOUT : failure.code();
        }

        private Duration timeLeft() {
            return Duration.between(clock.instant(), deadline);
        }

        private long millisSince(Instant startedAt) {
            return Duration.between(startedAt, clock.instant()).toMillis();
        }

        private AgentResult aborted() {
            return result(AgentResult.Status.ABORTED, "");
        }

        private AgentResult result(AgentResult.Status status, String answer) {
            return result(status, answer, null);
        }

        private AgentResult result(AgentResult.Status status, String answer, GuardrailOutcome outcome) {
            return new AgentResult(answer, usage, toolCallRecords.size(), iterations, status, null, llmCalls,
                    toolCallRecords, answeredBy, outcome);
        }

        private AgentResult failed(ErrorCode code) {
            if (retry != null) {
                return keepEarlierAnswer();
            }
            return AgentResult.failed(code, usage, toolCallRecords.size(), iterations, llmCalls, toolCallRecords,
                    answeredBy);
        }
    }

    /**
     * The answer the guardrail asked to have again: what it was, what was wrong with it and where it came from, and
     * whether the client has been told to drop it yet.
     */
    private static final class Retry {
        private final String answer;
        private final List<Violation> violations;
        private final ModelRoute answeredBy;
        private boolean revised;

        Retry(String answer, List<Violation> violations, ModelRoute answeredBy) {
            this.answer = answer;
            this.violations = violations;
            this.answeredBy = answeredBy;
        }
    }

    /** Last N turns of plain text, starting with a user turn so the roles alternate as the API requires. */
    private List<Message> conversationSoFar(List<ChatTurn> history) {
        int from = Math.max(0, history.size() - props.maxHistoryTurns());
        var recent = history.subList(from, history.size());
        var messages = new ArrayList<Message>();
        for (var turn : recent) {
            boolean user = turn.fromUser();
            if (messages.isEmpty() && !user) {
                continue;
            }
            boolean sameRoleAsPrevious = !messages.isEmpty()
                    && (messages.get(messages.size() - 1).role() == Message.Role.USER) == user;
            if (sameRoleAsPrevious) {
                continue;
            }
            messages.add(user ? Message.user(turn.text()) : Message.assistant(turn.text()));
        }
        if (!messages.isEmpty() && messages.get(messages.size() - 1).role() == Message.Role.USER) {
            messages.remove(messages.size() - 1);
        }
        return messages;
    }
}
