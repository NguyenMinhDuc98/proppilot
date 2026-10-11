package dev.proppilot.chat;

import dev.proppilot.agent.AgentResult;
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
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

/**
 * Usage record of one question: tokens, tool calls, latency and estimated cost, with the trace of the model calls and
 * tool executions it took. Saving a run saves its trace in the same transaction.
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
    /** Why the question went to the model it did, or null. */
    private String routedReason;
    /** What the grounding guardrail concluded about the answer, or null when it did not check it. */
    private String guardrailStatus;
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

    public ChatRun(UUID id, Instant createdAt, String question, String provider, String model, AgentResult result,
                   List<String> toolNames, long latencyMs, BigDecimal costUsd) {
        this.id = id;
        this.createdAt = createdAt;
        this.question = question;
        this.provider = provider;
        this.model = model;
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
        this.latencyMs = latencyMs;
        this.costUsd = costUsd;
    }
}
