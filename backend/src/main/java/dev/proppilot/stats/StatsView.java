package dev.proppilot.stats;

import java.math.BigDecimal;
import java.util.List;

public record StatsView(
        long totalRuns,
        BigDecimal totalCostUsd,
        BigDecimal avgCostUsd,
        long avgLatencyMs,
        double avgToolCalls,
        long totalInputTokens,
        long totalOutputTokens,
        List<ToolUsage> toolUsage) {

    public record ToolUsage(String tool, long calls) {
    }
}
