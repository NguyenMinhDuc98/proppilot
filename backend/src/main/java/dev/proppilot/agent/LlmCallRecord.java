package dev.proppilot.agent;

import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.StopReason;
import java.time.Instant;

/**
 * One model call of a run, as the agent loop measured it. The token counts are those of
 * {@link dev.proppilot.agent.llm.Usage}: {@code inputTokens} leaves out what was read from or written to the cache.
 *
 * @param sequence 1 for the first call of the run
 * @param stopReason how the model ended its reply, or null when the call failed
 * @param errorCode why the call failed, or null when it did not
 */
public record LlmCallRecord(int sequence, String model, int inputTokens, int outputTokens, int cacheReadTokens,
                            int cacheWriteTokens, long latencyMs, StopReason stopReason, Outcome outcome,
                            ErrorCode errorCode, Instant startedAt) {

    public enum Outcome { OK, ERROR }

    static LlmCallRecord succeeded(int sequence, String model, Instant startedAt, long latencyMs, LlmResponse response) {
        var usage = response.usage();
        return new LlmCallRecord(sequence, model, usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                usage.cacheWriteTokens(), latencyMs, response.stopReason(), Outcome.OK, null, startedAt);
    }

    /** A failed call reports no usage: the provider did not say what it had counted before it failed. */
    static LlmCallRecord failed(int sequence, String model, Instant startedAt, long latencyMs, ErrorCode code) {
        return new LlmCallRecord(sequence, model, 0, 0, 0, 0, latencyMs, null, Outcome.ERROR, code, startedAt);
    }
}
