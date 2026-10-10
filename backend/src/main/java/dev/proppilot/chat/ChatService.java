package dev.proppilot.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.AgentEvent;
import dev.proppilot.agent.AgentLoop;
import dev.proppilot.agent.AgentRequest;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runs the agent for one turn and streams its progress to the client as server-sent events:
 *
 * <ul>
 *   <li>{@code tool_call}: {@code {name, args}}</li>
 *   <li>{@code tool_result}: {@code {name, summary, error}}</li>
 *   <li>{@code action_proposed}: the proposed action ({@code id, kind, status, summaryEn, summaryAr, preview,
 *       expiresAt, ...}), right after the {@code tool_result} of the tool that proposed it</li>
 *   <li>{@code token}: {@code {text}}, a piece of the answer</li>
 *   <li>{@code error}: {@code {message, code}}</li>
 *   <li>{@code done}: the usage and outcome of the run</li>
 * </ul>
 *
 * <p>A turn is a question, or the confirmation of a decision a person took on a proposal ({@code actionId}). A
 * decision is confirmed once, but a run that ends without delivering the confirmation gives it back.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    /** The SSE connection outlives the run deadline by this much, so a run that times out can still say so. */
    private static final Duration EMITTER_GRACE = Duration.ofSeconds(15);

    private final AgentLoop agent;
    private final ActionFollowUp followUp;
    private final LlmClient llm;
    private final CostCalculator costs;
    private final ChatRunRepository runs;
    private final ObjectMapper json;
    private final Clock clock;
    private final long emitterTimeoutMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(AgentLoop agent, ActionFollowUp followUp, LlmClient llm, CostCalculator costs,
                       ChatRunRepository runs, ObjectMapper json, Clock clock, AgentProperties agentProps) {
        this.agent = agent;
        this.followUp = followUp;
        this.llm = llm;
        this.costs = costs;
        this.runs = runs;
        this.json = json;
        this.clock = clock;
        this.emitterTimeoutMs = agentProps.runTimeout().plus(EMITTER_GRACE).toMillis();
    }

    /**
     * @param visitor who is asking, as {@link dev.proppilot.actions.VisitorActors} names a visitor; only the visitor
     *                who decided on a proposal can have its decision confirmed
     * @throws dev.proppilot.actions.ActionException when the request confirms an action that is unknown, undecided,
     *                                               decided by someone else or already confirmed; nothing is streamed
     *                                               then
     */
    public SseEmitter start(ChatRequest request, String visitor) {
        var turn = turnOf(request, UUID.randomUUID(), visitor);
        var emitter = new SseEmitter(emitterTimeoutMs);
        var client = new ClientStream(emitter, json);
        executor.execute(() -> runAndStream(turn, request.actionId(), client));
        return emitter;
    }

    private AgentRequest turnOf(ChatRequest request, UUID runId, String visitor) {
        var history = request.historyOrEmpty();
        if (request.actionId() == null) {
            return AgentRequest.question(request.message(), history, runId);
        }
        return AgentRequest.confirmation(followUp.claim(request.actionId(), visitor), history, runId);
    }

    /** @param confirming the action whose decision this run confirms, or null when it answers a question */
    private void runAndStream(AgentRequest turn, UUID confirming, ClientStream client) {
        var started = System.nanoTime();
        var toolNames = new ArrayList<String>();
        try {
            var result = runAgent(turn, confirming, client, toolNames);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            var run = record(turn, result, toolNames, latencyMs);
            if (!client.isGone()) {
                finish(client, result, run);
            }
        } catch (RuntimeException e) {
            log.error("Chat run failed", e);
            client.completeWithError(e);
        }
    }

    /**
     * Runs the agent. A confirmation the run did not deliver is given back before anyone hears how the run ended, so a
     * retry sent on seeing the error finds it free.
     */
    private AgentResult runAgent(AgentRequest turn, UUID confirming, ClientStream client, List<String> toolNames) {
        AgentResult result;
        try {
            result = agent.run(turn, event -> {
                if (event instanceof AgentEvent.ToolCall call) {
                    toolNames.add(call.name());
                }
                send(client, event);
            }, client::isGone);
        } catch (RuntimeException failed) {
            giveBack(confirming);
            throw failed;
        }
        if (result.status() != AgentResult.Status.OK) {
            giveBack(confirming);
        }
        return result;
    }

    private void giveBack(UUID confirming) {
        if (confirming != null) {
            followUp.release(confirming);
        }
    }

    private void finish(ClientStream client, AgentResult result, ChatRun run) {
        if (result.status() == AgentResult.Status.ERROR) {
            client.send("error", ErrorPayload.of(result.errorCode()));
        }
        client.send("done", DonePayload.of(run, llm));
        client.complete();
    }

    private ChatRun record(AgentRequest turn, AgentResult result, List<String> toolNames, long latencyMs) {
        var cost = costs.cost(result.usage(), llm.billable());
        var run = new ChatRun(turn.runId(), Instant.now(clock), truncate(turn.message()), llm.provider(), llm.model(),
                result, toolNames, latencyMs, cost);
        return runs.save(run);
    }

    private static String truncate(String question) {
        return question.length() <= 1000 ? question : question.substring(0, 1000);
    }

    private static void send(ClientStream client, AgentEvent event) {
        switch (event) {
            case AgentEvent.ToolCall call -> client.send("tool_call", new ToolCallPayload(call.name(), call.args()));
            case AgentEvent.ToolResultEvent result -> {
                client.send("tool_result", new ToolResultPayload(result.name(), result.summary(), result.error()));
                if (result.ui() != null && ToolResult.ACTION_UI_TYPE.equals(result.ui().path("type").asText())) {
                    client.send("action_proposed", result.ui().get("action"));
                }
            }
            case AgentEvent.Token token -> client.send("token", new TokenPayload(token.text()));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    record ToolCallPayload(String name, Object args) {
    }

    record ToolResultPayload(String name, String summary, boolean error) {
    }

    record TokenPayload(String text) {
    }

    /** {@code message} is a generic English fallback; clients show their own text for {@code code}. */
    record ErrorPayload(String message, String code) {

        static ErrorPayload of(ErrorCode code) {
            return new ErrorPayload(code.message(), code.code());
        }
    }

    record DonePayload(UUID runId, String status, String provider, String model, int inputTokens, int outputTokens,
                       int toolCalls, int iterations, long latencyMs, BigDecimal costUsd, boolean truncated) {

        static DonePayload of(ChatRun run, LlmClient llm) {
            return new DonePayload(run.getId(), run.getStatus().name(), llm.provider(), llm.model(), run.getInputTokens(),
                    run.getOutputTokens(), run.getToolCalls(), run.getIterations(), run.getLatencyMs(), run.getCostUsd(),
                    run.getStatus() == AgentResult.Status.TRUNCATED);
        }
    }
}
