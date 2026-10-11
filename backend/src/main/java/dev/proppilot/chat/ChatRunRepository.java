package dev.proppilot.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ChatRunRepository extends JpaRepository<ChatRun, UUID>, JpaSpecificationExecutor<ChatRun> {

    /** A run counts as flagged when the guardrail recorded at least one violation for it. */
    @Query("""
            select count(r) as runs, sum(r.costUsd) as totalCost, avg(r.costUsd) as avgCost,
                   avg(r.latencyMs) as avgLatency, avg(r.toolCalls) as avgToolCalls,
                   sum(r.inputTokens) as inputTokens, sum(r.outputTokens) as outputTokens,
                   sum(r.cacheReadTokens) as cacheReadTokens, sum(r.cacheWriteTokens) as cacheWriteTokens,
                   sum(case when r.guardrailViolations > 0 then 1 else 0 end) as flaggedRuns
            from ChatRun r""")
    Totals totals();

    @Query("select t as tool, count(t) as calls from ChatRun r join r.toolNames t group by t order by count(t) desc, t")
    List<ToolCount> toolCounts();

    /** The model that gave the final answer of each run, so a run that fell back counts for the model that answered. */
    @Query("""
            select r.model as model, count(r) as runs, sum(r.costUsd) as costUsd
            from ChatRun r group by r.model order by count(r) desc, r.model""")
    List<ModelCount> modelCounts();

    interface Totals {
        long getRuns();

        java.math.BigDecimal getTotalCost();

        Double getAvgCost();

        Double getAvgLatency();

        Double getAvgToolCalls();

        Long getInputTokens();

        Long getOutputTokens();

        Long getCacheReadTokens();

        Long getCacheWriteTokens();

        Long getFlaggedRuns();
    }

    interface ToolCount {
        String getTool();

        long getCalls();
    }

    interface ModelCount {
        String getModel();

        long getRuns();

        java.math.BigDecimal getCostUsd();
    }
}
