package dev.proppilot.chat;

import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.llm.StopReason;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

/** A row of {@code run_llm_calls}: one model call of a stored run. */
@Embeddable
public record RunLlmCall(
        @Column(name = "seq") int sequence,
        String model,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheWriteTokens,
        long latencyMs,
        @Enumerated(EnumType.STRING) StopReason stopReason,
        @Enumerated(EnumType.STRING) LlmCallRecord.Outcome outcome,
        String errorCode,
        Instant startedAt) {

    static RunLlmCall of(LlmCallRecord call) {
        return new RunLlmCall(call.sequence(), call.model(), call.inputTokens(), call.outputTokens(),
                call.cacheReadTokens(), call.cacheWriteTokens(), call.latencyMs(), call.stopReason(), call.outcome(),
                call.errorCode() == null ? null : call.errorCode().code(), call.startedAt());
    }
}
