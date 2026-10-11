package dev.proppilot.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The stored trace of one run: its models, its tools, its figures. It holds no question and no answer, so the run's id
 * may be shared; the arguments and result summaries of the tool calls are the only text in it.
 *
 * @param model the model that gave the final answer
 */
public record RunTraceView(
        UUID runId,
        Instant createdAt,
        String status,
        String provider,
        String model,
        String routedReason,
        Guardrail guardrail,
        Totals totals,
        List<LlmCall> llmCalls,
        List<ToolCall> toolCalls) {

    public record Guardrail(String status, int violations) {
    }

    /** {@code inputTokens} are the uncached ones; the cache tokens are counted apart. */
    public record Totals(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens,
                         BigDecimal costUsd, long latencyMs, int iterations, int toolCalls) {
    }

    public record LlmCall(int seq, String model, int inputTokens, int outputTokens, int cacheReadTokens,
                          int cacheWriteTokens, long latencyMs, String stopReason, String outcome, String errorCode,
                          Instant startedAt) {
    }

    public record ToolCall(int seq, String name, String args, String summary, boolean error, long latencyMs) {
    }

    /** Call inside the transaction: the calls of the run are loaded when they are read here. */
    public static RunTraceView of(ChatRun run) {
        var llmCalls = run.getLlmCallRows().stream().map(RunTraceView::llmCall).toList();
        var toolCalls = run.getToolCallRows().stream().map(RunTraceView::toolCall).toList();
        var totals = new Totals(run.getInputTokens(), run.getOutputTokens(), run.getCacheReadTokens(),
                run.getCacheWriteTokens(), run.getCostUsd(), run.getLatencyMs(), run.getIterations(), run.getToolCalls());
        return new RunTraceView(run.getId(), run.getCreatedAt(), run.getStatus().name(), run.getProvider(),
                run.getModel(), run.getRoutedReason(),
                new Guardrail(run.getGuardrailStatus(), run.getGuardrailViolations()), totals, llmCalls, toolCalls);
    }

    private static LlmCall llmCall(RunLlmCall call) {
        return new LlmCall(call.sequence(), call.model(), call.inputTokens(), call.outputTokens(),
                call.cacheReadTokens(), call.cacheWriteTokens(), call.latencyMs(),
                call.stopReason() == null ? null : call.stopReason().name(), call.outcome().name(), call.errorCode(),
                call.startedAt());
    }

    private static ToolCall toolCall(RunToolCall call) {
        return new ToolCall(call.sequence(), call.toolName(), call.args(), call.summary(), call.error(),
                call.latencyMs());
    }
}
