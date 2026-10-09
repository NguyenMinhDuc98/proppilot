package dev.proppilot.agent.llm;

import dev.proppilot.agent.ErrorCode;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Turns Anthropic failures (HTTP statuses, stream error events, network errors) into {@link LlmException}s with the
 * right code and retry flag. Messages carry the status or error type only, never the response text.
 */
final class AnthropicErrors {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504, 529);

    private AnthropicErrors() {
    }

    static LlmException forStatus(int status, Optional<String> retryAfterHeader) {
        var message = "Claude API returned " + status;
        var code = switch (status) {
            case 401, 403 -> ErrorCode.LLM_AUTH;
            case 408 -> ErrorCode.LLM_TIMEOUT;
            case 429 -> ErrorCode.LLM_OVERLOADED;
            default -> status >= 500 ? ErrorCode.LLM_OVERLOADED : ErrorCode.LLM_ERROR;
        };
        if (!RETRYABLE_STATUSES.contains(status)) {
            return new LlmException(message, code);
        }
        return LlmException.retryable(message, code, retryAfter(retryAfterHeader), null);
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
        var code = cause instanceof HttpTimeoutException ? ErrorCode.LLM_TIMEOUT : ErrorCode.LLM_ERROR;
        return LlmException.retryable("Could not reach the Claude API: " + cause.getMessage(), code, null, cause);
    }

    /** The {@code Retry-After} header in seconds; the HTTP-date form is ignored. */
    private static Duration retryAfter(Optional<String> header) {
        try {
            return header.map(value -> Duration.ofSeconds(Long.parseLong(value.strip())))
                    .filter(wait -> !wait.isNegative())
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
