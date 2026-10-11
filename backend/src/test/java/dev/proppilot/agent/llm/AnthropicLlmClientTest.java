package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.config.LlmProperties;
import dev.proppilot.config.TestLlmProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real HTTP client against an in-process server that plays back scripted Claude responses. */
class AnthropicLlmClientTest {

    private static final String API_KEY = "test-api-key-123";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LlmRequest REQUEST =
            new LlmRequest("system", List.of(Message.user("hi")), List.of(), Duration.ofSeconds(60));

    /** A question, a tool call the model made and what the tool answered: the last block is a tool result. */
    private static final LlmRequest CONVERSATION = new LlmRequest("system",
            List.of(Message.user("how many units?"),
                    new Message(Message.Role.ASSISTANT, List.of(new ContentBlock.ToolUse("t1", "lookup",
                            JSON.createObjectNode().put("city", "Riyadh")))),
                    new Message(Message.Role.USER, List.of(new ContentBlock.ToolResult("t1", "6 units found", false)))),
            List.of(new ToolSpec("lookup", "Looks something up", JSON.createObjectNode().put("type", "object")),
                    new ToolSpec("count", "Counts something", JSON.createObjectNode().put("type", "object"))),
            Duration.ofSeconds(60));

    private static final String ANSWER_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String CACHED_ANSWER_STREAM = ANSWER_STREAM.replace(
            "\"usage\":{\"input_tokens\":12,\"output_tokens\":1}",
            "\"usage\":{\"input_tokens\":12,\"cache_creation_input_tokens\":300,\"cache_read_input_tokens\":4000,"
                    + "\"output_tokens\":1}");

    private static final String OVERLOADED_EVENT =
            "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n";

    /** {@code delay} holds back the headers; {@code stall} keeps the stream open and silent after the body. */
    private record Reply(int status, Map<String, String> headers, String body, Duration delay, Duration stall) {
        private static final Map<String, String> SSE = Map.of("content-type", "text/event-stream");

        static Reply status(int status) {
            return new Reply(status, Map.of(), "{\"error\":{\"message\":\"SECRET-UPSTREAM-DETAIL\"}}", Duration.ZERO, Duration.ZERO);
        }

        static Reply stream(String body) {
            return new Reply(200, SSE, body, Duration.ZERO, Duration.ZERO);
        }

        static Reply streamThatStallsAfter(String body, Duration stall) {
            return new Reply(200, SSE, body, Duration.ZERO, stall);
        }

        Reply after(Duration delay) {
            return new Reply(status, headers, body, delay, stall);
        }
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> deltas = new ArrayList<>();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var reply = replies.poll();
            if (reply == null) {
                reply = Reply.status(418);
            }
            pause(reply.delay());
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            boolean stalls = !reply.stall().isZero();
            exchange.sendResponseHeaders(reply.status(), stalls ? 0 : bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
                if (stalls) {
                    out.flush();
                    pause(reply.stall());
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AnthropicLlmClient client(int port) {
        return client(port, 5, 3);
    }

    private AnthropicLlmClient client(int port, int timeoutSeconds, int maxRetries) {
        return new AnthropicLlmClient(props(port, timeoutSeconds, maxRetries, 1000), new ObjectMapper(),
                new RetryPolicy(maxRetries, Duration.ofSeconds(1), sleeps::add, () -> 0.5));
    }

    private static LlmProperties props(int port, int timeoutSeconds, int maxRetries, int retryBaseDelayMs) {
        return TestLlmProperties.defaults().model("test-model").apiUrl("http://127.0.0.1:" + port + "/v1/messages")
                .timeoutSeconds(timeoutSeconds).retries(maxRetries, retryBaseDelayMs).build();
    }

    private AnthropicLlmClient clientWithCaching(boolean promptCaching) {
        var props = TestLlmProperties.defaults().model("test-model").promptCaching(promptCaching)
                .apiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages").build();
        return new AnthropicLlmClient(props, JSON);
    }

    private LlmResponse complete() {
        return client(server.getAddress().getPort()).complete(REQUEST, deltas::add);
    }

    @Test
    void sendsNoToolsFieldWhenNoToolsAreOffered() {
        var client = client(server.getAddress().getPort());
        var tool = new ToolSpec("lookup", "Looks something up", new ObjectMapper().createObjectNode());
        var withTool = new LlmRequest("system", List.of(Message.user("hi")), List.of(tool), Duration.ofSeconds(60));

        assertThat(client.buildBody(REQUEST).has("tools")).isFalse();
        assertThat(client.buildBody(withTool).get("tools")).hasSize(1);
    }

    @Test
    void asksTheModelTheRequestNamesAndOtherwiseItsOwn() throws Exception {
        replies.add(Reply.stream(ANSWER_STREAM));
        replies.add(Reply.stream(ANSWER_STREAM));
        var client = client(server.getAddress().getPort());
        var named = new LlmRequest("system", List.of(Message.user("hi")), List.of(), Duration.ofSeconds(60),
                "claude-sonnet-4-5");

        client.complete(named, deltas::add);
        client.complete(REQUEST, deltas::add);

        assertThat(JSON.readTree(bodies.get(0)).get("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(JSON.readTree(bodies.get(1)).get("model").asText()).isEqualTo("test-model");
    }

    @Test
    void marksTheLastToolTheSystemPromptAndTheLastMessageBlockForCachingByDefault() throws Exception {
        replies.add(Reply.stream(ANSWER_STREAM));

        client(server.getAddress().getPort()).complete(CONVERSATION, deltas::add);

        var body = JSON.readTree(bodies.get(0));
        var ephemeral = JSON.readTree("{\"type\":\"ephemeral\"}");
        assertThat(body.get("system")).isEqualTo(JSON.readTree(
                "[{\"type\":\"text\",\"text\":\"system\",\"cache_control\":{\"type\":\"ephemeral\"}}]"));
        assertThat(body.get("tools").get(0).has("cache_control")).isFalse();
        assertThat(body.get("tools").get(1).get("cache_control")).isEqualTo(ephemeral);
        var messages = body.get("messages");
        assertThat(messages.get(0).get("content").get(0).has("cache_control")).isFalse();
        assertThat(messages.get(1).get("content").get(0).has("cache_control")).isFalse();
        assertThat(messages.get(2).get("content").get(0).get("cache_control")).isEqualTo(ephemeral);
        assertThat(body.findValues("cache_control")).hasSize(3);
    }

    @Test
    void sendsTheRequestWithNoCacheMarksWhenPromptCachingIsOff() {
        replies.add(Reply.stream(ANSWER_STREAM));

        clientWithCaching(false).complete(CONVERSATION, deltas::add);

        assertThat(bodies.get(0)).isEqualTo("{\"model\":\"test-model\",\"max_tokens\":1024,\"stream\":true,"
                + "\"system\":\"system\",\"tools\":["
                + "{\"name\":\"lookup\",\"description\":\"Looks something up\",\"input_schema\":{\"type\":\"object\"}},"
                + "{\"name\":\"count\",\"description\":\"Counts something\",\"input_schema\":{\"type\":\"object\"}}],"
                + "\"messages\":["
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"how many units?\"}]},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"lookup\","
                + "\"input\":{\"city\":\"Riyadh\"}}]},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\","
                + "\"content\":\"6 units found\",\"is_error\":false}]}]}");
    }

    @Test
    void promptCachingChangesNothingInTheRequestButTheMarks() throws Exception {
        replies.add(Reply.stream(ANSWER_STREAM));
        replies.add(Reply.stream(ANSWER_STREAM));

        clientWithCaching(false).complete(CONVERSATION, deltas::add);
        clientWithCaching(true).complete(CONVERSATION, deltas::add);

        var off = JSON.readTree(bodies.get(0));
        var on = JSON.readTree(bodies.get(1));
        assertThat(on).isNotEqualTo(off);
        assertThat(withoutCacheMarks((ObjectNode) on)).isEqualTo(off);
        assertThat(on.fieldNames()).toIterable().containsExactlyElementsOf(() -> off.fieldNames());
    }

    @Test
    void marksOnlyTheSystemPromptAndTheConversationWhenNoToolsAreOffered() throws Exception {
        replies.add(Reply.stream(ANSWER_STREAM));

        clientWithCaching(true).complete(REQUEST, deltas::add);

        var body = JSON.readTree(bodies.get(0));
        assertThat(body.has("tools")).isFalse();
        assertThat(body.get("system").get(0).has("cache_control")).isTrue();
        assertThat(body.get("messages").get(0).get("content").get(0).has("cache_control")).isTrue();
        assertThat(body.findValues("cache_control")).hasSize(2);
    }

    /** The request as it would have been without caching: the system prompt a plain string and no marks. */
    private static JsonNode withoutCacheMarks(ObjectNode body) {
        var copy = body.deepCopy();
        copy.put("system", copy.get("system").get(0).get("text").asText());
        copy.findParents("cache_control").forEach(parent -> ((ObjectNode) parent).remove("cache_control"));
        return copy;
    }

    @Test
    void retriesOverloadedResponsesAndThenStreamsTheAnswer() {
        replies.add(Reply.status(529));
        replies.add(Reply.status(529));
        replies.add(Reply.stream(ANSWER_STREAM));

        var response = complete();

        assertThat(requests).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(response.text()).isEqualTo("Hello there");
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.usage()).isEqualTo(new Usage(12, 3));
        assertThat(deltas).containsExactly("Hello ", "there");
    }

    @Test
    void reportsTheCacheTokensTheApiReportsApartFromTheInputTokens() {
        replies.add(Reply.stream(CACHED_ANSWER_STREAM));

        var response = complete();

        assertThat(response.usage()).isEqualTo(new Usage(12, 3, 4000, 300));
    }

    @Test
    void waitsForTheRetryAfterHeader() {
        replies.add(new Reply(429, Map.of("Retry-After", "7"), "{}", Duration.ZERO, Duration.ZERO));
        replies.add(Reply.stream(ANSWER_STREAM));

        complete();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void aRetryAfterLongerThanTheTimeBudgetReportsTheOverloadInsteadOfWaitingForIt() {
        replies.add(new Reply(429, Map.of("Retry-After", "30"), "{}", Duration.ZERO, Duration.ZERO));
        replies.add(Reply.stream(ANSWER_STREAM));
        var request = new LlmRequest("system", List.of(Message.user("hi")), List.of(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client(server.getAddress().getPort()).complete(request, deltas::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED));

        assertThat(requests).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void authenticationFailuresAreNotRetried() {
        replies.add(Reply.status(401));

        assertThatThrownBy(this::complete).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.LLM_AUTH);
            assertThat(e.retryable()).isFalse();
        });

        assertThat(requests).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void givesUpAfterTheConfiguredRetriesAndReportsOverloaded() {
        for (int i = 0; i < 5; i++) {
            replies.add(Reply.status(503));
        }

        assertThatThrownBy(this::complete)
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED));

        assertThat(requests).hasValue(4);
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void retriesAStreamThatStartsWithAnOverloadedError() {
        replies.add(Reply.stream(OVERLOADED_EVENT));
        replies.add(Reply.stream(ANSWER_STREAM));

        var response = complete();

        assertThat(requests).hasValue(2);
        assertThat(response.text()).isEqualTo("Hello there");
        assertThat(deltas).containsExactly("Hello ", "there");
    }

    @Test
    void doesNotRetryOnceTextHasBeenStreamed() {
        var cutShort = ANSWER_STREAM.substring(0, ANSWER_STREAM.indexOf("event: content_block_stop")) + OVERLOADED_EVENT;
        replies.add(Reply.stream(cutShort));
        replies.add(Reply.stream(ANSWER_STREAM));

        assertThatThrownBy(this::complete)
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED));

        assertThat(requests).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(deltas).containsExactly("Hello ", "there");
    }

    @Test
    void retriesWhenNothingAnswersAtAll() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> client(closedPort).complete(REQUEST, deltas::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR));

        assertThat(sleeps).hasSize(3);
    }

    @Test
    void aStreamCutByMaxTokensWithAnIncompleteToolCallKeepsTheTextAndDropsTheCall() {
        replies.add(Reply.stream("""
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me check"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"search_units","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\": \\"Ri"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":2048}}

                event: message_stop
                data: {"type":"message_stop"}

                """));

        var response = complete();

        assertThat(requests).hasValue(1);
        assertThat(response.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(response.text()).isEqualTo("Let me check");
        assertThat(response.toolUses()).isEmpty();
    }

    @Test
    void theRequestsTimeBudgetCapsTheCallEvenWhenTheConfiguredTimeoutIsLonger() {
        replies.add(Reply.stream(ANSWER_STREAM).after(Duration.ofSeconds(3)));
        var request = new LlmRequest("system", List.of(Message.user("hi")), List.of(), Duration.ofMillis(300));
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> client(server.getAddress().getPort()).complete(request, deltas::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_TIMEOUT));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(requests).hasValue(1);
        assertThat(deltas).isEmpty();
    }

    @Test
    void aStreamThatGoesSilentAfterTheHeadersIsCutOffWhenTheTimeBudgetRunsOut() {
        var firstDeltaOnly = ANSWER_STREAM.substring(0, ANSWER_STREAM.lastIndexOf("event: content_block_delta"));
        replies.add(Reply.streamThatStallsAfter(firstDeltaOnly, Duration.ofSeconds(10)));
        var request = new LlmRequest("system", List.of(Message.user("hi")), List.of(), Duration.ofMillis(1500));
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> client(server.getAddress().getPort()).complete(request, deltas::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_TIMEOUT));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(6));
        assertThat(deltas).containsExactly("Hello ");
        assertThat(requests).hasValue(1);
    }

    @Test
    void aStreamThatGoesSilentBeforeAnyTextIsCutOffAtTheConfiguredTimeoutAndRetried() {
        var onlyMessageStart = ANSWER_STREAM.substring(0, ANSWER_STREAM.indexOf("event: content_block_start"));
        replies.add(Reply.streamThatStallsAfter(onlyMessageStart, Duration.ofSeconds(10)));
        replies.add(Reply.stream(ANSWER_STREAM));

        var response = client(server.getAddress().getPort(), 1, 3).complete(REQUEST, deltas::add);

        assertThat(response.text()).isEqualTo("Hello there");
        assertThat(requests).hasValue(2);
        assertThat(sleeps).hasSize(1);
        assertThat(deltas).containsExactly("Hello ", "there");
    }

    @Test
    void theConfiguredTimeoutCapsTheCallEvenWhenTheRequestsTimeBudgetIsLonger() {
        replies.add(Reply.stream(ANSWER_STREAM).after(Duration.ofSeconds(4)));
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> client(server.getAddress().getPort(), 1, 0).complete(REQUEST, deltas::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_TIMEOUT));

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        assertThat(deltas).isEmpty();
    }

    @Test
    void theClientBuiltFromTheConfigurationRetriesAsOftenAsConfigured() {
        for (int i = 0; i < 3; i++) {
            replies.add(Reply.status(503));
        }
        var configured = new AnthropicLlmClient(props(server.getAddress().getPort(), 5, 1, 1), new ObjectMapper());

        assertThatThrownBy(() -> configured.complete(REQUEST, deltas::add))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED));

        assertThat(requests).hasValue(2);
    }

    @Test
    void anUnreadableStreamFailsOnceWithoutQuotingIt() {
        replies.add(Reply.stream("data: SECRET-UPSTREAM-DETAIL {not json\n\n"));

        assertThatThrownBy(this::complete).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).doesNotContain("SECRET-UPSTREAM-DETAIL");
        });

        assertThat(requests).hasValue(1);
    }

    @Test
    void neitherTheUpstreamBodyNorTheKeyReachTheExceptionMessage() {
        for (int i = 0; i < 4; i++) {
            replies.add(Reply.status(500));
        }

        assertThatThrownBy(this::complete)
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Claude API returned 500")
                        .doesNotContain("SECRET-UPSTREAM-DETAIL")
                        .doesNotContain(API_KEY));
    }
}
