package dev.proppilot.admin;

import dev.proppilot.chat.ChatRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminRunView(
        Instant createdAt,
        String question,
        String status,
        String provider,
        String model,
        List<String> toolNames,
        int inputTokens,
        int outputTokens,
        BigDecimal costUsd,
        long latencyMs) {

    /** Call inside the transaction: copying the lazy tool names loads them before the session closes. */
    static AdminRunView of(ChatRun run) {
        return new AdminRunView(run.getCreatedAt(), run.getQuestion(), run.getStatus().name(), run.getProvider(),
                run.getModel(), List.copyOf(run.getToolNames()), run.getInputTokens(), run.getOutputTokens(),
                run.getCostUsd(), run.getLatencyMs());
    }
}
