package dev.proppilot.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.config.LlmProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Calls the Claude Messages API over plain HTTP with streaming enabled. */
@Component
@ConditionalOnProperty(name = "proppilot.llm.provider", havingValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {

    private static final String API_VERSION = "2023-06-01";

    private final LlmProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AnthropicLlmClient(LlmProperties props, ObjectMapper json) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is required when LLM_PROVIDER=anthropic");
        }
        this.props = props;
        this.json = json;
    }

    @Override
    public LlmResponse complete(LlmRequest request, Consumer<String> onTextDelta) {
        var httpRequest = HttpRequest.newBuilder(URI.create(props.apiUrl()))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("content-type", "application/json")
                .header("x-api-key", props.apiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request).toString()))
                .build();
        try {
            var response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new LlmException(describeFailure(response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8)));
                }
                var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                return new AnthropicStreamParser(json, onTextDelta).parse(reader);
            }
        } catch (IOException e) {
            throw new LlmException("Could not reach the Claude API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request interrupted", e);
        }
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return props.model();
    }

    ObjectNode buildBody(LlmRequest request) {
        var body = json.createObjectNode();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("stream", true);
        body.put("system", request.system());
        var tools = body.putArray("tools");
        for (var tool : request.tools()) {
            tools.addObject()
                    .put("name", tool.name())
                    .put("description", tool.description())
                    .set("input_schema", tool.inputSchema());
        }
        var messages = body.putArray("messages");
        for (var message : request.messages()) {
            var node = messages.addObject().put("role", message.role().name().toLowerCase());
            addBlocks(node.putArray("content"), message);
        }
        return body;
    }

    private void addBlocks(ArrayNode content, Message message) {
        for (var block : message.content()) {
            switch (block) {
                case ContentBlock.Text text -> content.addObject().put("type", "text").put("text", text.text());
                case ContentBlock.ToolUse use -> content.addObject()
                        .put("type", "tool_use").put("id", use.id()).put("name", use.name()).set("input", use.input());
                case ContentBlock.ToolResult result -> content.addObject()
                        .put("type", "tool_result").put("tool_use_id", result.toolUseId())
                        .put("content", result.content()).put("is_error", result.error());
            }
        }
    }

    private String describeFailure(int status, String body) {
        String detail;
        try {
            detail = json.readTree(body).path("error").path("message").asText(body);
        } catch (IOException e) {
            detail = "unreadable response";
        }
        return "Claude API returned " + status + ": " + detail;
    }
}
