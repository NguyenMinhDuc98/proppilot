package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.config.LlmProperties;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real HTTP client against an in-process server that plays back scripted Claude responses. */
class AnthropicLlmClientTest {

    private static final String API_KEY = "test-api-key-123";
    private static final LlmRequest REQUEST =
            new LlmRequest("system", List.of(Message.user("hi")), List.of(), Duration.ofSeconds(60));

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
            exchange.getRequestBody().readAllBytes();
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
        var props = new LlmProperties("anthropic", API_KEY, "test-model", "http://127.0.0.1:" + port + "/v1/messages",
                1024, timeoutSeconds, maxRetries, 1000, BigDecimal.ONE, BigDecimal.ONE);
        return new AnthropicLlmClient(props, new ObjectMapper(),
                new RetryPolicy(maxRetries, Duration.ofSeconds(1), sleeps::add, () -> 0.5));
    }

    private LlmResponse complete() {
        return client(server.getAddress().getPort()).complete(REQUEST, deltas::add);
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
    void waitsForTheRetryAfterHeader() {
        replies.add(new Reply(429, Map.of("Retry-After", "7"), "{}", Duration.ZERO, Duration.ZERO));
        replies.add(Reply.stream(ANSWER_STREAM));

        complete();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
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
