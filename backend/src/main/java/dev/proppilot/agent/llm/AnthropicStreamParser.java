package dev.proppilot.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns an Anthropic Messages API server-sent-event stream into one {@link LlmResponse}, forwarding text deltas. */
final class AnthropicStreamParser {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamParser.class);

    private final ObjectMapper json;
    private final Consumer<String> onTextDelta;

    private final List<ContentBlock> blocks = new ArrayList<>();
    private StringBuilder text;
    private String toolId;
    private String toolName;
    private StringBuilder toolJson;

    private int inputTokens;
    private int outputTokens;
    private StopReason stopReason = StopReason.OTHER;

    AnthropicStreamParser(ObjectMapper json, Consumer<String> onTextDelta) {
        this.json = json;
        this.onTextDelta = onTextDelta;
    }

    LlmResponse parse(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                handle(json.readTree(line.substring(5).strip()));
            }
        }
        return new LlmResponse(List.copyOf(blocks), stopReason, new Usage(inputTokens, outputTokens));
    }

    private void handle(JsonNode event) throws JsonProcessingException {
        switch (event.path("type").asText()) {
            case "message_start" -> {
                var usage = event.path("message").path("usage");
                inputTokens = usage.path("input_tokens").asInt()
                        + usage.path("cache_creation_input_tokens").asInt()
                        + usage.path("cache_read_input_tokens").asInt();
                outputTokens = usage.path("output_tokens").asInt();
            }
            case "content_block_start" -> startBlock(event.path("content_block"));
            case "content_block_delta" -> applyDelta(event.path("delta"));
            case "content_block_stop" -> finishBlock();
            case "message_delta" -> {
                stopReason = mapStopReason(event.path("delta").path("stop_reason").asText());
                outputTokens = event.path("usage").path("output_tokens").asInt(outputTokens);
            }
            case "error" -> {
                var error = event.path("error");
                log.warn("Claude stream reported an error: {}", error);
                throw AnthropicErrors.forStreamError(error.path("type").asText("unknown"));
            }
            default -> { }
        }
    }

    private void startBlock(JsonNode block) {
        if ("tool_use".equals(block.path("type").asText())) {
            toolId = block.path("id").asText();
            toolName = block.path("name").asText();
            toolJson = new StringBuilder();
        } else {
            text = new StringBuilder();
        }
    }

    private void applyDelta(JsonNode delta) {
        switch (delta.path("type").asText()) {
            case "text_delta" -> {
                var chunk = delta.path("text").asText();
                if (text != null) {
                    text.append(chunk);
                    onTextDelta.accept(chunk);
                }
            }
            case "input_json_delta" -> {
                if (toolJson != null) {
                    toolJson.append(delta.path("partial_json").asText());
                }
            }
            default -> { }
        }
    }

    private void finishBlock() throws JsonProcessingException {
        if (toolName != null) {
            var raw = toolJson.isEmpty() ? "{}" : toolJson.toString();
            blocks.add(new ContentBlock.ToolUse(toolId, toolName, json.readTree(raw)));
            toolId = null;
            toolName = null;
            toolJson = null;
        } else if (text != null) {
            if (!text.isEmpty()) {
                blocks.add(new ContentBlock.Text(text.toString()));
            }
            text = null;
        }
    }

    private static StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }
}
