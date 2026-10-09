package dev.proppilot.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.AgentEvent;
import dev.proppilot.agent.AgentLoop;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runs the agent for one question and streams its progress to the client as server-sent events. */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    /** The SSE connection outlives the run deadline by this much, so a run that times out can still say so. */
    private static final Duration EMITTER_GRACE = Duration.ofSeconds(15);

    private final AgentLoop agent;
    private final LlmClient llm;
    private final CostCalculator costs;
    private final ChatRunRepository runs;
    private final ObjectMapper json;
    private final Clock clock;
    private final long emitterTimeoutMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(AgentLoop agent, LlmClient llm, CostCalculator costs, ChatRunRepository runs,
                       ObjectMapper json, Clock clock, AgentProperties agentProps) {
        this.agent = agent;
        this.llm = llm;
        this.costs = costs;
        this.runs = runs;
        this.json = json;
        this.clock = clock;
        this.emitterTimeoutMs = agentProps.runTimeout().plus(EMITTER_GRACE).toMillis();
    }

    public SseEmitter start(ChatRequest request) {
        var emitter = new SseEmitter(emitterTimeoutMs);
        var client = new ClientStream(emitter, json);
        executor.execute(() -> runAndStream(request, client));
        return emitter;
    }

    private void runAndStream(ChatRequest request, ClientStream client) {
        var started = System.nanoTime();
        var toolNames = new ArrayList<String>();
        try {
            var result = agent.run(request.message(), request.historyOrEmpty(), event -> {
                if (event instanceof AgentEvent.ToolCall call) {
                    toolNames.add(call.name());
                }
                send(client, event);
            }, client::isGone);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            var run = record(request.message(), result, toolNames, latencyMs);
            if (!client.isGone()) {
                finish(client, result, run);
            }
        } catch (RuntimeException e) {
            log.error("Chat run failed", e);
            client.completeWithError(e);
        }
    }

    private void finish(ClientStream client, AgentResult result, ChatRun run) {
        if (result.status() == AgentResult.Status.ERROR) {
            client.send("error", ErrorPayload.of(result.errorCode()));
        }
        client.send("done", DonePayload.of(run, llm));
        client.complete();
    }

    private ChatRun record(String question, AgentResult result, ArrayList<String> toolNames, long latencyMs) {
        var cost = costs.cost(result.usage(), llm.billable());
        var run = new ChatRun(UUID.randomUUID(), Instant.now(clock), truncate(question), llm.provider(), llm.model(),
                result, toolNames, latencyMs, cost);
        return runs.save(run);
    }

    private static String truncate(String question) {
        return question.length() <= 1000 ? question : question.substring(0, 1000);
    }

    private static void send(ClientStream client, AgentEvent event) {
        switch (event) {
            case AgentEvent.ToolCall call -> client.send("tool_call", new ToolCallPayload(call.name(), call.args()));
            case AgentEvent.ToolResultEvent result ->
                    client.send("tool_result", new ToolResultPayload(result.name(), result.summary(), result.error()));
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
