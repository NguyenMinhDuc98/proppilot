package dev.proppilot.agent.llm;

import dev.proppilot.agent.ErrorCode;
import java.time.Duration;
import java.util.Optional;

/**
 * A failed model call. The message is for the server log only; the browser gets {@link #code()} and a generic text,
 * so never copy provider response text into the message.
 */
public class LlmException extends RuntimeException {

    private final ErrorCode code;
    private final boolean retryable;
    private final Duration retryAfter;

    /** A failure that trying again cannot fix. */
    public LlmException(String message, ErrorCode code) {
        this(message, code, false, null, null);
    }

    public LlmException(String message, ErrorCode code, Throwable cause) {
        this(message, code, false, null, cause);
    }

    /** A transient failure; {@code retryAfter} is how long the provider asked us to wait, or null. */
    public static LlmException retryable(String message, ErrorCode code, Duration retryAfter, Throwable cause) {
        return new LlmException(message, code, true, retryAfter, cause);
    }

    public static LlmException retryable(String message, ErrorCode code) {
        return retryable(message, code, null, null);
    }

    private LlmException(String message, ErrorCode code, boolean retryable, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public ErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
