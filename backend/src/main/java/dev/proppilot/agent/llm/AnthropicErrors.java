package dev.proppilot.agent.llm;

import dev.proppilot.agent.ErrorCode;
import java.io.IOException;
import java.util.Optional;

/**
 * Turns Anthropic failures (HTTP statuses, stream error events, network errors) into {@link LlmException}s with the
 * right code and retry flag. Messages carry the status or error type only, never the response text.
 */
final class AnthropicErrors {

    private static final String PROVIDER = "Claude API";

    private AnthropicErrors() {
    }

    static LlmException forStatus(int status, Optional<String> retryAfterHeader) {
        return ProviderErrors.forStatus(PROVIDER, status, retryAfterHeader);
    }

    /** An {@code error} event inside a 200 response stream. */
    static LlmException forStreamError(String errorType) {
        var message = "Claude stream reported " + errorType;
        return switch (errorType) {
            case "overloaded_error" -> LlmException.retryable(message, ErrorCode.LLM_OVERLOADED);
            case "authentication_error", "permission_error" -> new LlmException(message, ErrorCode.LLM_AUTH);
            default -> new LlmException(message, ErrorCode.LLM_ERROR);
        };
    }

    /** The request failed before any response arrived. */
    static LlmException unreachable(IOException cause) {
        return ProviderErrors.unreachable(PROVIDER, cause);
    }
}
