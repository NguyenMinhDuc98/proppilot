package dev.proppilot.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record StatsView(
        long totalRuns,
        BigDecimal totalCostUsd,
        BigDecimal avgCostUsd,
        long avgLatencyMs,
        double avgToolCalls,
        long totalInputTokens,
        long totalOutputTokens,
        List<ToolUsage> toolUsage,
        List<RecentRun> recentRuns) {

    public record ToolUsage(String tool, long calls) {
    }

    public record RecentRun(Instant at, String question, int toolCalls, long latencyMs, BigDecimal costUsd, String status) {
    }
}
