package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.ErrorCode;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The status table is covered through {@link AnthropicErrorsTest}; this is what differs between providers. */
class ProviderErrorsTest {

    @Test
    void theMessageNamesTheProviderAndTheStatusAndNothingElse() {
        var failure = ProviderErrors.forStatus("Voyage API", 429, Optional.of("9"));

        assertThat(failure.getMessage()).isEqualTo("Voyage API returned 429");
        assertThat(failure.code()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(9));
    }

    @Test
    void anUnreachableProviderIsNamed() {
        var failure = ProviderErrors.unreachable("Voyage API", new HttpTimeoutException("request timed out"));

        assertThat(failure.getMessage()).isEqualTo("Could not reach the Voyage API: request timed out");
        assertThat(failure.code()).isEqualTo(ErrorCode.LLM_TIMEOUT);
        assertThat(failure.retryable()).isTrue();
        assertThat(ProviderErrors.unreachable("Voyage API", new IOException("refused")).code())
                .isEqualTo(ErrorCode.LLM_ERROR);
    }
}
