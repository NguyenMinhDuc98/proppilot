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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Usage record of one question: tokens, tool calls, latency and estimated cost. */
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
    private int inputTokens;
    private int outputTokens;
    private int toolCalls;
    private int iterations;
    private long latencyMs;
    private BigDecimal costUsd;

    @Enumerated(EnumType.STRING)
    private AgentResult.Status status;

    @ElementCollection
    @CollectionTable(name = "chat_run_tools", joinColumns = @JoinColumn(name = "run_id"))
    @Column(name = "tool_name")
    private List<String> toolNames;

    public ChatRun(UUID id, Instant createdAt, String question, String provider, String model, AgentResult result,
                   List<String> toolNames, long latencyMs, BigDecimal costUsd) {
        this.id = id;
        this.createdAt = createdAt;
        this.question = question;
        this.provider = provider;
        this.model = model;
        this.inputTokens = result.usage().inputTokens();
        this.outputTokens = result.usage().outputTokens();
        this.toolCalls = result.toolCalls();
        this.iterations = result.iterations();
        this.status = result.status();
        this.toolNames = List.copyOf(toolNames);
        this.latencyMs = latencyMs;
        this.costUsd = costUsd;
    }
}
