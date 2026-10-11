package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What the parser makes of the usage a stream reports. The fixtures under {@code src/test/resources/anthropic} have the
 * shape the Messages API documents (cache counts in {@code message_start}, running totals in {@code message_delta});
 * they were written by hand, not recorded from the live API.
 */
class AnthropicStreamParserUsageTest {

    private final ObjectMapper json = new ObjectMapper();

    private LlmResponse parseFixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/anthropic/" + name)) {
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return new AnthropicStreamParser(json, text -> { }).parse(reader);
        }
    }

    @Test
    void aCallThatWritesTheCacheReportsTheWrittenTokensApartFromTheInput() throws IOException {
        var response = parseFixture("cache-write.sse");

        assertThat(response.usage()).isEqualTo(new Usage(14, 9, 0, 4312));
        assertThat(response.text()).isEqualTo("There are 150 units.");
    }

    @Test
    void aCallThatReadsTheCacheReportsTheReadTokensApartFromTheInput() throws IOException {
        var response = parseFixture("cache-read.sse");

        assertThat(response.usage()).isEqualTo(new Usage(23, 27, 4312, 0));
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.toolUses()).singleElement().satisfies(use -> assertThat(use.name()).isEqualTo("get_occupancy_summary"));
    }

    @Test
    void theRunningTotalsOfMessageDeltaReplaceWhatMessageStartHad() throws IOException {
        var response = parseFixture("cache-in-message-delta-only.sse");

        assertThat(response.usage()).isEqualTo(new Usage(31, 6, 2048, 512));
    }

    @Test
    void aStreamWithoutCacheFieldsHasNoCacheTokens() throws IOException {
        var stream = """
                data: {"type":"message_start","message":{"usage":{"input_tokens":25,"output_tokens":1}}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}
                """;

        var response = new AnthropicStreamParser(json, text -> { }).parse(new BufferedReader(new StringReader(stream)));

        assertThat(response.usage()).isEqualTo(new Usage(25, 42, 0, 0));
    }

    @Test
    void nullCacheFieldsCountAsZero() throws IOException {
        var stream = """
                data: {"type":"message_start","message":{"usage":{"input_tokens":25,"cache_creation_input_tokens":null,"cache_read_input_tokens":null,"output_tokens":1}}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}
                """;

        var response = new AnthropicStreamParser(json, text -> { }).parse(new BufferedReader(new StringReader(stream)));

        assertThat(response.usage()).isEqualTo(new Usage(25, 3, 0, 0));
    }

    @Test
    void usageAddsUpEveryCount() {
        var total = new Usage(10, 5, 100, 20).plus(new Usage(1, 2, 3, 4));

        assertThat(total).isEqualTo(new Usage(11, 7, 103, 24));
        assertThat(Usage.ZERO.plus(total)).isEqualTo(total);
    }
}
