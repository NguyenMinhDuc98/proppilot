package dev.proppilot.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatRunRepository extends JpaRepository<ChatRun, UUID> {

    @Query("""
            select count(r) as runs, sum(r.costUsd) as totalCost, avg(r.costUsd) as avgCost,
                   avg(r.latencyMs) as avgLatency, avg(r.toolCalls) as avgToolCalls,
                   sum(r.inputTokens) as inputTokens, sum(r.outputTokens) as outputTokens
            from ChatRun r""")
    Totals totals();

    @Query("select t as tool, count(t) as calls from ChatRun r join r.toolNames t group by t order by count(t) desc, t")
    List<ToolCount> toolCounts();

    interface Totals {
        long getRuns();

        java.math.BigDecimal getTotalCost();

        Double getAvgCost();

        Double getAvgLatency();

        Double getAvgToolCalls();

        Long getInputTokens();

        Long getOutputTokens();
    }

    interface ToolCount {
        String getTool();

        long getCalls();
    }
}
