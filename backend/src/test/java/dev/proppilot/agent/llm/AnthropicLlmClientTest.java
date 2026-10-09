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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real HTTP client against an in-process server that plays back scripted Claude responses. */
class AnthropicLlmClientTest {

    private static final String API_KEY = "test-api-key-123";
    private static final LlmRequest REQUEST = new LlmRequest("system", List.of(Message.user("hi")), List.of());

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

    private record Reply(int status, Map<String, String> headers, String body) {
        static Reply status(int status) {
            return new Reply(status, Map.of(), "{\"error\":{\"message\":\"SECRET-UPSTREAM-DETAIL\"}}");
        }

        static Reply stream(String body) {
            return new Reply(200, Map.of("content-type", "text/event-stream"), body);
        }
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> deltas = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            var reply = replies.poll();
            if (reply == null) {
                reply = Reply.status(418);
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicLlmClient client(int port) {
        var props = new LlmProperties("anthropic", API_KEY, "test-model", "http://127.0.0.1:" + port + "/v1/messages",
                1024, 5, 3, 1000, BigDecimal.ONE, BigDecimal.ONE);
        return new AnthropicLlmClient(props, new ObjectMapper(), new RetryPolicy(3, Duration.ofSeconds(1), sleeps::add, () -> 0.5));
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
        replies.add(new Reply(429, Map.of("Retry-After", "7"), "{}"));
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
