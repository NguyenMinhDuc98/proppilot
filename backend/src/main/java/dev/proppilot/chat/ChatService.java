package dev.proppilot.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.AgentEvent;
import dev.proppilot.agent.AgentLoop;
import dev.proppilot.agent.AgentRequest;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.ToolCallRecord;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.guardrail.ClaimKind;
import dev.proppilot.guardrail.GuardrailOutcome;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.config.AgentProperties;
import dev.proppilot.trace.RunTracer;
import dev.proppilot.trace.TracedRun;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *   <li>{@code tool_result}: {@code {name, summary, error}}. The result of a lease search also carries
 *       {@code sources}: the clauses it found, each with {@code ref, documentCode, documentTitle, unitCode,
 *       tenantNameEn, tenantNameAr, language, sectionNo, sectionTitle, page, text, pdfPath}, for the web app to show
 *       next to the answer. The field is there for that tool only, and the model never sees it</li>
 *   <li>{@code action_proposed}: the proposed action ({@code id, kind, status, summaryEn, summaryAr, preview,
 *       expiresAt, ...}), right after the {@code tool_result} of the tool that proposed it</li>
 *   <li>{@code token}: {@code {text}}, a piece of the answer</li>
 *   <li>{@code revise}: {@code {reason}}, where {@code reason} is {@code grounding}. The answer streamed so far is
 *       to be dropped: the grounding guardrail found values in it that nothing the run was given supports, and a
 *       new answer follows (guardrail mode {@code retry}). It comes as the new answer begins. If that answer never
 *       completes, it comes once more, followed by the earlier answer again as {@code token}, and the run ends
 *       with {@code guardrail} and {@code done} as if it had not been asked again</li>
 *   <li>{@code error}: {@code {message, code}}</li>
 *   <li>{@code guardrail}: {@code {status, values}}, only for an answer that states values the run cannot ground
 *       ({@code status} is always {@code unverified}; {@code values} are {@code {kind, value}} with {@code kind}
 *       one of {@code NUMBER}, {@code UNIT_CODE}, {@code TENANT_NAME} and {@code CITATION}, at most 10, each cut
 *       to 40 characters). It is sent before {@code done}</li>
 *   <li>{@code done}: the usage and outcome of the run: status, provider, the model that answered and why it was
 *       chosen ({@code routedReason}), the token counts (input, output and the cache read and write tokens), tool
 *       calls, iterations, latency and cost, and {@code guardrailStatus}: {@code OK}, {@code FLAGGED},
 *       {@code RETRIED_OK} or {@code RETRIED_FLAGGED}, and null when the guardrail did not check the answer</li>
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
    private final RunTracer tracer;
    private final ObjectMapper json;
    private final Clock clock;
    private final long emitterTimeoutMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(AgentLoop agent, ActionFollowUp followUp, LlmClient llm, CostCalculator costs,
                       ChatRunRepository runs, RunTracer tracer, ObjectMapper json, Clock clock,
                       AgentProperties agentProps) {
        this.agent = agent;
        this.followUp = followUp;
        this.llm = llm;
        this.costs = costs;
        this.runs = runs;
        this.tracer = tracer;
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
        var startedAt = Instant.now(clock);
        var started = System.nanoTime();
        try {
            var result = runAgent(turn, confirming, client);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            var run = record(turn, result, latencyMs);
            tracer.record(traced(run, result, startedAt));
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
    private AgentResult runAgent(AgentRequest turn, UUID confirming, ClientStream client) {
        AgentResult result;
        try {
            result = agent.run(turn, event -> send(client, event), client::isGone);
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
        if (result.guardrail() != null && result.guardrail().flagged()) {
            client.send("guardrail", GuardrailPayload.of(result.guardrail()));
        }
        client.send("done", DonePayload.of(run));
        client.complete();
    }

    /** The tool names are those of the trace, which fit their column whatever name the model chose. */
    private ChatRun record(AgentRequest turn, AgentResult result, long latencyMs) {
        var cost = costs.cost(result.llmCallRecords(), llm.billable());
        var toolNames = result.toolCallRecords().stream().map(ToolCallRecord::toolName).toList();
        var run = new ChatRun(turn.runId(), Instant.now(clock), truncate(turn.message()), llm.provider(), result,
                toolNames, latencyMs, cost);
        return runs.save(run);
    }

    private static TracedRun traced(ChatRun run, AgentResult result, Instant startedAt) {
        return new TracedRun(run.getId(), run.getProvider(), run.getModel(), result.status(), result.errorCode(),
                run.getRoutedReason(), run.getGuardrailStatus(), result.iterations(), result.toolCalls(), startedAt,
                run.getLatencyMs(), result.llmCallRecords(),
                result.toolCallRecords().stream().map(TracedRun.Tool::of).toList());
    }

    private static String truncate(String question) {
        return question.length() <= 1000 ? question : question.substring(0, 1000);
    }

    private static void send(ClientStream client, AgentEvent event) {
        switch (event) {
            case AgentEvent.ToolCall call -> client.send("tool_call", new ToolCallPayload(call.name(), call.args()));
            case AgentEvent.ToolResultEvent result -> {
                client.send("tool_result", new ToolResultPayload(result.name(), result.summary(), result.error(),
                        sourcesOf(result.ui())));
                if (isUiOfType(result.ui(), ToolResult.ACTION_UI_TYPE)) {
                    client.send("action_proposed", result.ui().get("action"));
                }
            }
            case AgentEvent.Token token -> client.send("token", new TokenPayload(token.text()));
            case AgentEvent.Revise revise -> client.send("revise", new RevisePayload(revise.reason()));
        }
    }

    /** The clauses a lease search found, or null for every other result, which then has no such field. */
    private static JsonNode sourcesOf(JsonNode ui) {
        return isUiOfType(ui, ToolResult.SOURCES_UI_TYPE) ? ui.get("sources") : null;
    }

    private static boolean isUiOfType(JsonNode ui, String type) {
        return ui != null && type.equals(ui.path("type").asText());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    record ToolCallPayload(String name, Object args) {
    }

    record ToolResultPayload(String name, String summary, boolean error,
                             @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode sources) {
    }

    record TokenPayload(String text) {
    }

    record RevisePayload(String reason) {
    }

    /** The claims of an answer that its run cannot ground; they are the figures, codes, names and refs it wrote. */
    record GuardrailPayload(String status, List<Value> values) {

        record Value(ClaimKind kind, String value) {
        }

        static GuardrailPayload of(GuardrailOutcome outcome) {
            return new GuardrailPayload("unverified",
                    outcome.violations().stream().map(v -> new Value(v.kind(), v.value())).toList());
        }
    }

    /** {@code message} is a generic English fallback; clients show their own text for {@code code}. */
    record ErrorPayload(String message, String code) {

        static ErrorPayload of(ErrorCode code) {
            return new ErrorPayload(code.message(), code.code());
        }
    }

    /**
     * The token counts are the run's totals; {@code inputTokens} are the uncached ones, apart from the cache tokens.
     * {@code model} is the one that answered and {@code routedReason} why: {@code DEFAULT}, {@code COMPLEX},
     * {@code GUARDRAIL_RETRY} or {@code FALLBACK}. {@code guardrailStatus} is null for an answer that was not checked.
     */
    record DonePayload(UUID runId, String status, String provider, String model, String routedReason,
                       String guardrailStatus, int inputTokens, int outputTokens, int cacheReadTokens,
                       int cacheWriteTokens, int toolCalls, int iterations, long latencyMs, BigDecimal costUsd,
                       boolean truncated) {

        static DonePayload of(ChatRun run) {
            return new DonePayload(run.getId(), run.getStatus().name(), run.getProvider(), run.getModel(),
                    run.getRoutedReason(), run.getGuardrailStatus(), run.getInputTokens(), run.getOutputTokens(),
                    run.getCacheReadTokens(), run.getCacheWriteTokens(), run.getToolCalls(), run.getIterations(),
                    run.getLatencyMs(), run.getCostUsd(), run.getStatus() == AgentResult.Status.TRUNCATED);
        }
    }
}
