package dev.proppilot.agent;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public AgentLoop(LlmClient llm, ToolRegistry tools, AgentProperties props, Clock clock, ModelRouter router) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.clock = clock;
        this.router = router;
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
        private Usage usage = Usage.ZERO;
        private int iterations;
        /** Where the next call goes. */
        private ModelRoute route;
        /** Where the last call that was answered went, or where the run was sent while none was: what the result reports. */
        private ModelRoute answeredBy;
        /** The code of the failure that sent the run to the other model. Set once: a run falls back only once. */
        private ErrorCode fellBackFrom;

        Run(AgentRequest request, Consumer<AgentEvent> events, BooleanSupplier cancelled) {
            this.events = events;
            this.cancelled = cancelled;
            this.toolsOffered = request.toolsOffered();
            this.toolSpecs = toolsOffered ? tools.specs() : List.of();
            this.context = new ToolContext(request.runId());
            messages.addAll(conversationSoFar(request.history()));
            // A confirmation only words an outcome that is settled, so it has no question to score.
            this.route = toolsOffered ? router.route(request.message(), messages.size()) : router.defaultRoute();
            this.answeredBy = route;
            messages.add(Message.user(request.message()));
        }

        AgentResult execute() {
            while (iterations < props.maxIterations()) {
                var finished = step();
                if (finished.isPresent()) {
                    return finished.get();
                }
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
                return Optional.of(result(AgentResult.Status.TRUNCATED, response.text()));
            }
            messages.add(new Message(Message.Role.ASSISTANT, response.content()));

            var requested = response.toolUses();
            if (requested.isEmpty()) {
                return Optional.of(result(AgentResult.Status.OK, response.text()));
            }
            return runTools(requested);
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
                events.accept(new AgentEvent.ToolCall(call.name(), call.input()));
                var startedAt = clock.instant();
                var result = execute(call);
                toolCallRecords.add(new ToolCallRecord(toolCallRecords.size() + 1, call.name(), call.input().toString(),
                        result.summary(), result.error(), millisSince(startedAt), startedAt));
                events.accept(new AgentEvent.ToolResultEvent(call.name(), result.summary(), result.error(), result.ui()));
                results.add(new ContentBlock.ToolResult(call.id(), result.content(), result.error()));
            }
            messages.add(new Message(Message.Role.USER, results));
            return Optional.empty();
        }

        /**
         * A run that offered no tools runs none, even if the model asks for one. A tool that limits its calls per run
         * is not run beyond that: the call is answered with an error that tells the model to work with what it has.
         */
        private ToolResult execute(ContentBlock.ToolUse call) {
            if (!toolsOffered) {
                return ToolResult.error("No tools are available for this reply.");
            }
            int calls = callsByTool.merge(call.name(), 1, Integer::sum);
            var limit = tools.maxCallsPerRun(call.name());
            if (limit.isPresent() && calls > limit.getAsInt()) {
                log.warn("Tool {} was called {} times in one run, over its limit of {}", call.name(), calls,
                        limit.getAsInt());
                return ToolResult.error("This reply may call " + call.name() + " at most " + limit.getAsInt()
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
            return new AgentResult(answer, usage, toolCallRecords.size(), iterations, status, null, llmCalls,
                    toolCallRecords, answeredBy);
        }

        private AgentResult failed(ErrorCode code) {
            return AgentResult.failed(code, usage, toolCallRecords.size(), iterations, llmCalls, toolCallRecords,
                    answeredBy);
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
