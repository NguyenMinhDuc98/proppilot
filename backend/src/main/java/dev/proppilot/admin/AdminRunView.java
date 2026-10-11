package dev.proppilot.admin;

import dev.proppilot.chat.ChatRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A run in the admin list. {@code inputTokens} are the uncached ones; {@code guardrailStatus} and
 * {@code routedReason} are null for a run the guardrail did not check or that was not routed, and
 * {@code guardrailViolations} counts the values of the answer that it could not ground.
 */
public record AdminRunView(
        UUID id,
        Instant createdAt,
        String question,
        String status,
        String provider,
        String model,
        String routedReason,
        String guardrailStatus,
        int guardrailViolations,
        List<String> toolNames,
        int iterations,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheWriteTokens,
        BigDecimal costUsd,
        long latencyMs) {

    /** Call inside the transaction: copying the lazy tool names loads them before the session closes. */
    static AdminRunView of(ChatRun run) {
        return new AdminRunView(run.getId(), run.getCreatedAt(), run.getQuestion(), run.getStatus().name(),
                run.getProvider(), run.getModel(), run.getRoutedReason(), run.getGuardrailStatus(),
                run.getGuardrailViolations(), List.copyOf(run.getToolNames()), run.getIterations(), run.getInputTokens(), run.getOutputTokens(),
                run.getCacheReadTokens(), run.getCacheWriteTokens(), run.getCostUsd(), run.getLatencyMs());
    }
}
