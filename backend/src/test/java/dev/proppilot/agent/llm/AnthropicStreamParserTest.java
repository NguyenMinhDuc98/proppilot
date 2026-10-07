package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AnthropicStreamParserTest {

    private static final String TEXT_AND_TOOL_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":25,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"check."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"search_units","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\": \\"Ri"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"yadh\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}

            event: message_stop
            data: {"type":"message_stop"}
            """;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void assemblesTextToolCallUsageAndStopReason() throws IOException {
        var deltas = new ArrayList<String>();

        var response = new AnthropicStreamParser(json, deltas::add).parse(new BufferedReader(new StringReader(TEXT_AND_TOOL_STREAM)));

        assertThat(deltas).containsExactly("Let me ", "check.");
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.usage()).isEqualTo(new Usage(25, 42));
        assertThat(response.text()).isEqualTo("Let me check.");
        assertThat(response.toolUses()).singleElement().satisfies(use -> {
            assertThat(use.id()).isEqualTo("toolu_1");
            assertThat(use.name()).isEqualTo("search_units");
            assertThat(use.input().get("city").asText()).isEqualTo("Riyadh");
        });
    }

    @Test
    void toolCallWithoutArgumentsGetsEmptyObject() throws IOException {
        var stream = """
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"find_overdue_tenants","input":{}}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}
                """;

        var response = new AnthropicStreamParser(json, s -> { }).parse(new BufferedReader(new StringReader(stream)));

        assertThat(response.toolUses().get(0).input().isObject()).isTrue();
        assertThat(response.toolUses().get(0).input()).isEmpty();
    }

    @Test
    void streamedErrorEventBecomesLlmException() {
        var stream = "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n";

        assertThatThrownBy(() -> new AnthropicStreamParser(json, s -> { }).parse(new BufferedReader(new StringReader(stream))))
                .isInstanceOf(LlmException.class).hasMessageContaining("Overloaded");
    }
}
