package dev.proppilot.stats;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregates over all runs. {@code totalInputTokens} counts uncached input only; what the prompt cache served or took
 * in is under {@code cache}.
 */
public record StatsView(
        long totalRuns,
        BigDecimal totalCostUsd,
        BigDecimal avgCostUsd,
        long avgLatencyMs,
        double avgToolCalls,
        long totalInputTokens,
        long totalOutputTokens,
        List<ToolUsage> toolUsage,
        List<ModelUsage> byModel,
        CacheUsage cache,
        GuardrailUsage guardrail) {

    public record ToolUsage(String tool, long calls) {
    }

    public record ModelUsage(String model, long runs, BigDecimal costUsd) {
    }

    public record CacheUsage(long readTokens, long writeTokens) {
    }

    public record GuardrailUsage(long flaggedRuns) {
    }
}
