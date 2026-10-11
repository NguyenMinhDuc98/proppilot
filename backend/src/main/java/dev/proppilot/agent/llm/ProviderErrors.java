package dev.proppilot.agent.llm;

import dev.proppilot.agent.ErrorCode;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the failures of an HTTP provider (statuses and network errors) into {@link LlmException}s with the right code
 * and retry flag, for every provider the same way. Messages carry the provider's name and the status only, never the
 * response text.
 */
public final class ProviderErrors {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504, 529);

    private ProviderErrors() {
    }

    /** @param provider how the provider is named in log lines, for example {@code Voyage API} */
    public static LlmException forStatus(String provider, int status, Optional<String> retryAfterHeader) {
        var message = provider + " returned " + status;
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

    /** The request failed before any response arrived. */
    public static LlmException unreachable(String provider, IOException cause) {
        var code = cause instanceof HttpTimeoutException ? ErrorCode.LLM_TIMEOUT : ErrorCode.LLM_ERROR;
        return LlmException.retryable("Could not reach the " + provider + ": " + cause.getMessage(), code, null, cause);
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
