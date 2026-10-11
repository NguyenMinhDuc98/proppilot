package dev.proppilot.chat;

import dev.proppilot.agent.AgentResult;
import dev.proppilot.guardrail.GuardrailOutcome;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

/**
 * Usage record of one question: tokens, tool calls, latency and estimated cost, with the trace of the model calls and
 * tool executions it took, the model that answered and why it was chosen, and what the grounding guardrail found in the
 * answer. Saving a run saves its trace in the same transaction.
 */
@Entity
@Table(name = "chat_runs")
@Getter
@NoArgsConstructor
public class ChatRun {

    @Id
    private UUID id;
    private Instant createdAt;
    private String question;
    private String provider;
    private String model;
    /** Uncached input tokens only: what the prompt cache served or took in is counted in the two columns below. */
    private int inputTokens;
    private int outputTokens;
    private int cacheReadTokens;
    private int cacheWriteTokens;
    private int toolCalls;
    private int iterations;
    private long latencyMs;
    private BigDecimal costUsd;
    /** Why the question went to the model it did, or null for a run recorded before routing. */
    private String routedReason;
    /** How the question scored when its model was chosen, or null when it was not scored. */
    private Integer complexityScore;
    /** The names of the rules the question met, comma separated; null with the score. */
    private String complexityRules;
    /** What the grounding guardrail concluded about the answer, or null when it did not check it. */
    private String guardrailStatus;
    /** How many values of the answer it could not ground; they are in {@code guardrailFindingRows}. */
    private int guardrailViolations;

    @Enumerated(EnumType.STRING)
    private AgentResult.Status status;

    /** Batched so a list of runs loads all its tool names in one query instead of one per run. */
    @BatchSize(size = 100)
    @ElementCollection
    @CollectionTable(name = "chat_run_tools", joinColumns = @JoinColumn(name = "run_id"))
    @Column(name = "tool_name")
    private List<String> toolNames;

    @ElementCollection
    @CollectionTable(name = "run_llm_calls", joinColumns = @JoinColumn(name = "run_id"))
    @OrderBy("sequence")
    private List<RunLlmCall> llmCallRows;

    @ElementCollection
    @CollectionTable(name = "run_tool_calls", joinColumns = @JoinColumn(name = "run_id"))
    @OrderBy("sequence")
    private List<RunToolCall> toolCallRows;

    @ElementCollection
    @CollectionTable(name = "run_guardrail_findings", joinColumns = @JoinColumn(name = "run_id"))
    @OrderBy("sequence")
    private List<RunGuardrailFinding> guardrailFindingRows;

    public ChatRun(UUID id, Instant createdAt, String question, String provider, AgentResult result,
                   List<String> toolNames, long latencyMs, BigDecimal costUsd) {
        this.id = id;
        this.createdAt = createdAt;
        this.question = question;
        this.provider = provider;
        this.model = result.route().model();
        this.routedReason = result.route().reason().name();
        var complexity = result.route().complexity();
        if (complexity != null) {
            this.complexityScore = complexity.score();
            this.complexityRules = complexity.rules().stream().map(Enum::name).collect(Collectors.joining(","));
        }
        this.inputTokens = result.usage().inputTokens();
        this.outputTokens = result.usage().outputTokens();
        this.cacheReadTokens = result.usage().cacheReadTokens();
        this.cacheWriteTokens = result.usage().cacheWriteTokens();
        this.toolCalls = result.toolCalls();
        this.iterations = result.iterations();
        this.status = result.status();
        this.toolNames = List.copyOf(toolNames);
        this.llmCallRows = result.llmCallRecords().stream().map(RunLlmCall::of).toList();
        this.toolCallRows = result.toolCallRecords().stream().map(RunToolCall::of).toList();
        var guardrail = result.guardrail();
        if (guardrail != null) {
            this.guardrailStatus = guardrail.status().name();
            this.guardrailViolations = guardrail.violations().size();
        }
        this.guardrailFindingRows = findings(guardrail);
        this.latencyMs = latencyMs;
        this.costUsd = costUsd;
    }

    private static List<RunGuardrailFinding> findings(GuardrailOutcome guardrail) {
        if (guardrail == null) {
            return List.of();
        }
        var rows = new ArrayList<RunGuardrailFinding>();
        for (var violation : guardrail.violations()) {
            rows.add(RunGuardrailFinding.of(rows.size() + 1, violation));
        }
        return rows;
    }
}
