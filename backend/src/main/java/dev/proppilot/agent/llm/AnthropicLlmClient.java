package dev.proppilot.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.config.LlmProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Calls the Claude Messages API over plain HTTP with streaming enabled, retrying transient failures. */
@Component
@ConditionalOnProperty(name = "proppilot.llm.provider", havingValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String API_VERSION = "2023-06-01";
    private static final int LOGGED_ERROR_BODY_BYTES = 500;

    private final LlmProperties props;
    private final ObjectMapper json;
    private final RetryPolicy retries;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public AnthropicLlmClient(LlmProperties props, ObjectMapper json) {
        this(props, json, RetryPolicy.withBackoff(props.maxRetries(), Duration.ofMillis(props.retryBaseDelayMs())));
    }

    AnthropicLlmClient(LlmProperties props, ObjectMapper json, RetryPolicy retries) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is required when LLM_PROVIDER=anthropic");
        }
        this.props = props;
        this.json = json;
        this.retries = retries;
    }

    @Override
    public LlmResponse complete(LlmRequest request, Consumer<String> onTextDelta) {
        long startedAt = System.nanoTime();
        return retries.execute(onTextDelta, sink -> attempt(request, timeoutLeft(request, startedAt), sink));
    }

    /** The configured limit, or less when the caller's time budget (shared by all retries) is nearly used up. */
    private Duration timeoutLeft(LlmRequest request, long startedAt) {
        var left = request.timeout().minus(Duration.ofNanos(System.nanoTime() - startedAt));
        if (!left.isPositive()) {
            throw new LlmException("No time left for another Claude call", ErrorCode.LLM_TIMEOUT);
        }
        var configured = Duration.ofSeconds(props.timeoutSeconds());
        return left.compareTo(configured) < 0 ? left : configured;
    }

    /** One try. {@code timeout} covers waiting for the headers and reading the whole streamed reply. */
    private LlmResponse attempt(LlmRequest request, Duration timeout, Consumer<String> onTextDelta) {
        long startedAt = System.nanoTime();
        var httpRequest = HttpRequest.newBuilder(URI.create(props.apiUrl()))
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("x-api-key", props.apiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request).toString()))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw AnthropicErrors.unreachable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request interrupted", ErrorCode.LLM_ERROR, e);
        }
        var body = response.body();
        var watchdog = new StreamWatchdog(body, timeout.minus(Duration.ofNanos(System.nanoTime() - startedAt)));
        try (body; watchdog) {
            if (response.statusCode() != 200) {
                throw rejected(response, body);
            }
            var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            return new AnthropicStreamParser(json, onTextDelta).parse(reader);
        } catch (IOException e) {
            if (watchdog.expired()) {
                throw LlmException.retryable("Claude response stream timed out", ErrorCode.LLM_TIMEOUT, null, e);
            }
            // Not e.getMessage(): a parse error quotes the response text.
            throw new LlmException("Claude response stream failed (" + e.getClass().getSimpleName() + ")", ErrorCode.LLM_ERROR, e);
        }
    }

    /** Logs what the API said (never the key) and returns an exception that carries only the status. */
    private LlmException rejected(HttpResponse<?> response, InputStream body) {
        log.warn("Claude API returned {}: {}", response.statusCode(), readForLog(body));
        return AnthropicErrors.forStatus(response.statusCode(), response.headers().firstValue("retry-after"));
    }

    private static String readForLog(InputStream body) {
        try {
            return new String(body.readNBytes(LOGGED_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unreadable)";
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
}
