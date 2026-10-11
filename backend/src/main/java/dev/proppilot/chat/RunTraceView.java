package dev.proppilot.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The stored trace of one run: its models, its tools, its figures. It holds no free text: not the question, not the
 * answer, and not what a tool was asked or said, because those carry the words of the visitor (a lease search is
 * asked in the words of the question). Only identifiers, names, counts, timings and outcomes are in it, so it may be
 * public. What the tools were asked and said is in the admin's detail of the run.
 *
 * @param model the model that gave the final answer
 * @param routedReason why it was chosen: {@code DEFAULT}, {@code COMPLEX}, {@code GUARDRAIL_RETRY} or
 *                     {@code FALLBACK}; null for a run recorded before routing
 * @param complexity how the question scored, or null when it was not scored
 */
public record RunTraceView(
        UUID runId,
        Instant createdAt,
        String status,
        String provider,
        String model,
        String routedReason,
        Complexity complexity,
        Guardrail guardrail,
        Totals totals,
        List<LlmCall> llmCalls,
        List<ToolCall> toolCalls) {

    /** {@code rules} are the names of the {@code ComplexityRule}s the question met. */
    public record Complexity(int score, List<String> rules) {
    }

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

    public record ToolCall(int seq, String name, boolean error, long latencyMs) {
    }

    /** Call inside the transaction: the calls of the run are loaded when they are read here. */
    public static RunTraceView of(ChatRun run) {
        var llmCalls = run.getLlmCallRows().stream().map(RunTraceView::llmCall).toList();
        var toolCalls = run.getToolCallRows().stream().map(RunTraceView::toolCall).toList();
        var totals = new Totals(run.getInputTokens(), run.getOutputTokens(), run.getCacheReadTokens(),
                run.getCacheWriteTokens(), run.getCostUsd(), run.getLatencyMs(), run.getIterations(), run.getToolCalls());
        return new RunTraceView(run.getId(), run.getCreatedAt(), run.getStatus().name(), run.getProvider(),
                run.getModel(), run.getRoutedReason(), complexity(run),
                new Guardrail(run.getGuardrailStatus(), run.getGuardrailViolations()), totals, llmCalls, toolCalls);
    }

    private static Complexity complexity(ChatRun run) {
        if (run.getComplexityScore() == null) {
            return null;
        }
        var rules = run.getComplexityRules().isEmpty() ? List.<String>of() : List.of(run.getComplexityRules().split(","));
        return new Complexity(run.getComplexityScore(), rules);
    }

    private static LlmCall llmCall(RunLlmCall call) {
        return new LlmCall(call.sequence(), call.model(), call.inputTokens(), call.outputTokens(),
                call.cacheReadTokens(), call.cacheWriteTokens(), call.latencyMs(),
                call.stopReason() == null ? null : call.stopReason().name(), call.outcome().name(), call.errorCode(),
                call.startedAt());
    }

    private static ToolCall toolCall(RunToolCall call) {
        return new ToolCall(call.sequence(), call.toolName(), call.error(), call.latencyMs());
    }
}
