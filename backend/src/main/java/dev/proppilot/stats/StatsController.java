package dev.proppilot.stats;

import dev.proppilot.chat.ChatRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final ChatRunRepository runs;

    public StatsController(ChatRunRepository runs) {
        this.runs = runs;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public StatsView stats() {
        var totals = runs.totals();
        var tools = runs.toolCounts().stream().map(t -> new StatsView.ToolUsage(t.getTool(), t.getCalls())).toList();
        var recent = runs.findTop10ByOrderByCreatedAtDesc().stream()
                .map(r -> new StatsView.RecentRun(r.getCreatedAt(), r.getQuestion(), r.getToolCalls(), r.getLatencyMs(),
                        r.getCostUsd(), r.getStatus().name()))
                .toList();
        return new StatsView(
                totals.getRuns(),
                scale(totals.getTotalCost()),
                scale(totals.getAvgCost() == null ? null : BigDecimal.valueOf(totals.getAvgCost())),
                totals.getAvgLatency() == null ? 0 : Math.round(totals.getAvgLatency()),
                totals.getAvgToolCalls() == null ? 0 : Math.round(totals.getAvgToolCalls() * 100) / 100.0,
                totals.getInputTokens() == null ? 0 : totals.getInputTokens(),
                totals.getOutputTokens() == null ? 0 : totals.getOutputTokens(),
                tools, recent);
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }
}
