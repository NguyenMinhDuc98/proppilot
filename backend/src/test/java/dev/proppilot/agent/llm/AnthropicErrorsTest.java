package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.ErrorCode;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnthropicErrorsTest {

    @ParameterizedTest
    @CsvSource({
            "408, LLM_TIMEOUT,    true",
            "429, LLM_OVERLOADED, true",
            "500, LLM_OVERLOADED, true",
            "502, LLM_OVERLOADED, true",
            "503, LLM_OVERLOADED, true",
            "504, LLM_OVERLOADED, true",
            "529, LLM_OVERLOADED, true",
            "501, LLM_OVERLOADED, false",
            "401, LLM_AUTH,       false",
            "403, LLM_AUTH,       false",
            "400, LLM_ERROR,      false",
            "404, LLM_ERROR,      false",
            "413, LLM_ERROR,      false",
    })
    void classifiesHttpStatuses(int status, ErrorCode code, boolean retryable) {
        var failure = AnthropicErrors.forStatus(status, Optional.empty());

        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.getMessage()).isEqualTo("Claude API returned " + status);
    }

    @Test
    void readsRetryAfterInSeconds() {
        var failure = AnthropicErrors.forStatus(429, Optional.of(" 12 "));

        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(12));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Wed, 21 Oct 2026 07:28:00 GMT
            soon
            -5
            """)
    void ignoresRetryAfterValuesItCannotUse(String header) {
        assertThat(AnthropicErrors.forStatus(429, Optional.of(header)).retryAfter()).isEmpty();
    }

    @Test
    void streamErrorsMapToCodesByType() {
        assertThat(AnthropicErrors.forStreamError("overloaded_error"))
                .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED))
                .satisfies(e -> assertThat(e.retryable()).isTrue());
        assertThat(AnthropicErrors.forStreamError("authentication_error"))
                .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_AUTH))
                .satisfies(e -> assertThat(e.retryable()).isFalse());
        assertThat(AnthropicErrors.forStreamError("invalid_request_error"))
                .satisfies(e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR))
                .satisfies(e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    void networkFailuresBeforeAnyResponseAreRetryableAndTimeoutsGetTheirOwnCode() {
        var timeout = AnthropicErrors.unreachable(new HttpTimeoutException("request timed out"));
        var refused = AnthropicErrors.unreachable(new IOException("connection refused"));

        assertThat(timeout.code()).isEqualTo(ErrorCode.LLM_TIMEOUT);
        assertThat(refused.code()).isEqualTo(ErrorCode.LLM_ERROR);
        assertThat(timeout.retryable()).isTrue();
        assertThat(refused.retryable()).isTrue();
    }
}
