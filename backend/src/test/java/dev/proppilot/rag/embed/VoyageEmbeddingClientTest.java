package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.agent.llm.RetryPolicy;
import dev.proppilot.config.EmbeddingProperties;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The real HTTP client against an in-process server that plays back scripted Voyage responses. */
class VoyageEmbeddingClientTest {

    private static final String API_KEY = "pa-test-voyage-key-123";
    private static final String UPSTREAM_DETAIL = "SECRET-UPSTREAM-DETAIL";
    private static final int DIMENSIONS = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the server was sent. */
    private record Received(String method, String path, String authorization, String contentType, JsonNode body) {
        List<String> inputs() {
            var inputs = new ArrayList<String>();
            body.get("input").forEach(text -> inputs.add(text.asText()));
            return inputs;
        }
    }

    /** {@code body == null} means a good reply with one vector for each text that was sent. */
    private record Reply(int status, Map<String, String> headers, String body, Duration delay) {

        static Reply ok() {
            return new Reply(200, Map.of(), null, Duration.ZERO);
        }

        static Reply json(String body) {
            return new Reply(200, Map.of(), body, Duration.ZERO);
        }

        static Reply status(int status) {
            return new Reply(status, Map.of(), "{\"detail\":\"" + UPSTREAM_DETAIL + "\"}", Duration.ZERO);
        }

        static Reply status(int status, String header, String value) {
            return new Reply(status, Map.of(header, value), "{}", Duration.ZERO);
        }

        Reply after(Duration delay) {
            return new Reply(status, headers, body, delay);
        }
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/v1/embeddings", exchange -> {
            var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            var reply = replies.poll();
            if (reply == null) {
                reply = Reply.ok();
            }
            pause(reply.delay());
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            var bytes = (reply.body() == null ? goodReply(body.get("input").size()) : reply.body())
                    .getBytes(StandardCharsets.UTF_8);
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
        handlers.shutdownNow();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The vector of the text at {@code index} of a request: its position, then three fixed numbers. */
    private static float[] vector(int index) {
        return new float[] {index, 0.5f, 0.25f, -1f};
    }

    private static String goodReply(int count) {
        var items = IntStream.range(0, count).mapToObj(index -> item(index, index)).toList();
        return "{\"object\":\"list\",\"data\":[" + String.join(",", items) + "],\"model\":\"test-model\","
                + "\"usage\":{\"total_tokens\":" + count * 10 + "}}";
    }

    private static String item(int index, int vectorOf) {
        var vector = vector(vectorOf);
        return "{\"object\":\"embedding\",\"embedding\":[" + vector[0] + "," + vector[1] + "," + vector[2] + ","
                + vector[3] + "],\"index\":" + index + "}";
    }

    private VoyageEmbeddingClient client() {
        return client(5, 3);
    }

    private VoyageEmbeddingClient client(int timeoutSeconds, int maxRetries) {
        return new VoyageEmbeddingClient(props(server.getAddress().getPort(), timeoutSeconds, maxRetries, API_KEY),
                JSON, new RetryPolicy(maxRetries, Duration.ofSeconds(1), sleeps::add, () -> 0.5));
    }

    private static EmbeddingProperties props(int port, int timeoutSeconds, int maxRetries, String key) {
        return new EmbeddingProperties("voyage", key, "test-model", DIMENSIONS,
                "http://127.0.0.1:" + port + "/v1/embeddings", timeoutSeconds, maxRetries, 1000);
    }

    @Test
    void sendsDocumentsToTheEmbeddingsEndpointWithTheKeyAsABearerToken() {
        var vectors = client().embedDocuments(List.of("first clause", "second clause"));

        assertThat(received).hasSize(1);
        var request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/embeddings");
        assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY);
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.body().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("input", "model", "input_type", "output_dimension");
        assertThat(request.inputs()).containsExactly("first clause", "second clause");
        assertThat(request.body().get("model").asText()).isEqualTo("test-model");
        assertThat(request.body().get("input_type").asText()).isEqualTo("document");
        assertThat(request.body().get("output_dimension").asInt()).isEqualTo(DIMENSIONS);
        assertThat(vectors).containsExactly(vector(0), vector(1));
    }

    @Test
    void sendsAQuestionAsAQuery() {
        var vector = client().embedQuery("how long is the notice period?");

        assertThat(received).hasSize(1);
        assertThat(received.get(0).body().get("input_type").asText()).isEqualTo("query");
        assertThat(received.get(0).inputs()).containsExactly("how long is the notice period?");
        assertThat(vector).containsExactly(vector(0));
    }

    @Test
    void sendsAtMostThirtyTwoTextsInARequestAndKeepsTheOrder() {
        var texts = IntStream.range(0, 70).mapToObj(i -> "clause " + i).toList();

        var vectors = client().embedDocuments(texts);

        assertThat(received).extracting(request -> request.inputs().size()).containsExactly(32, 32, 6);
        assertThat(received.stream().flatMap(request -> request.inputs().stream())).containsExactlyElementsOf(texts);
        assertThat(vectors).hasSize(70);
        assertThat(vectors.get(33)).isEqualTo(vector(1));
    }

    @Test
    void textWithOddCharactersStillMakesARequestTheApiCanRead() {
        client().embedDocuments(List.of("clause \u0000 with a nul", "clause \ud800 with a lone surrogate",
                "clause 😀 \"quoted\" \\ back\nslash"));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).inputs()).hasSize(3);
        assertThat(received.get(0).inputs().get(2)).isEqualTo("clause 😀 \"quoted\" \\ back\nslash");
    }

    @Test
    void anEmptyListCallsNobody() {
        assertThat(client().embedDocuments(List.of())).isEmpty();

        assertThat(received).isEmpty();
    }

    @Test
    void placesEachVectorByTheIndexTheReplyGivesIt() {
        replies.add(Reply.json("{\"data\":[" + item(1, 7) + "," + item(0, 3) + "]}"));

        var vectors = client().embedDocuments(List.of("a", "b"));

        assertThat(vectors).containsExactly(vector(3), vector(7));
    }

    @Test
    void waitsForTheRetryAfterHeaderOnATooManyRequestsReplyAndThenSucceeds() {
        replies.add(Reply.status(429, "Retry-After", "7"));

        var vectors = client().embedDocuments(List.of("a"));

        assertThat(received).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
        assertThat(vectors).containsExactly(vector(0));
    }

    @Test
    void retriesServerErrorsWithBackoffAndGivesUpAfterTheConfiguredRetries() {
        for (int i = 0; i < 5; i++) {
            replies.add(Reply.status(503));
        }

        assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED);
                    assertThat(e.getMessage()).isEqualTo("Voyage API returned 503");
                });

        assertThat(received).hasSize(4);
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void aRetryAfterThatIsAsLongAsAQuestionMayWaitReportsTheFailureInsteadOfWaiting() {
        replies.add(Reply.status(429, "Retry-After", "20"));

        assertThatThrownBy(() -> client().embedQuery("question"))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_OVERLOADED);
                    assertThat(e.retryable()).isTrue();
                });

        assertThat(received).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aBatchOfDocumentsMayWaitLongerThanAQuestion() {
        replies.add(Reply.status(429, "Retry-After", "20"));

        client().embedDocuments(List.of("clause"));

        assertThat(received).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(20));
    }

    @Test
    void aRejectedKeyIsReportedAsAnAuthenticationFailureAndNotRetried() {
        replies.add(Reply.status(401));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_AUTH);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("Voyage API returned 401");
                });

        assertThat(received).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aForbiddenKeyIsAnAuthenticationFailureToo() {
        replies.add(Reply.status(403));

        assertThatThrownBy(() -> client().embedQuery("question"))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_AUTH));
    }

    @Test
    void otherClientErrorsAreFailuresThatAreNotRetried() {
        replies.add(Reply.status(400));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR);
                    assertThat(e.retryable()).isFalse();
                });

        assertThat(received).hasSize(1);
    }

    @Test
    void neitherTheUpstreamBodyNorTheKeyReachAnExceptionMessage() {
        for (int status : new int[] {400, 401, 429, 500}) {
            replies.add(Reply.status(status));
            for (int i = 0; i < 4; i++) {
                replies.add(Reply.status(status));
            }
            assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                    .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.toString())
                            .doesNotContain(UPSTREAM_DETAIL).doesNotContain(API_KEY));
            replies.clear();
        }
    }

    @Test
    void theLogNeverHoldsTheKeyEvenWhenTheApiEchoesIt() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(VoyageEmbeddingClient.class);
        logger.addAppender(appender);
        try {
            replies.add(new Reply(401, Map.of(), "{\"detail\":\"Bearer " + API_KEY + " is invalid\"}", Duration.ZERO));

            assertThatThrownBy(() -> client().embedQuery("question")).isInstanceOf(LlmException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(API_KEY));
    }

    @Test
    void aReplyThatIsNotJsonIsRejectedWithoutQuotingIt() {
        replies.add(Reply.json("<html>" + UPSTREAM_DETAIL + "</html>"));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.toString()).doesNotContain(UPSTREAM_DETAIL);
                });

        assertThat(received).hasSize(1);
    }

    @Test
    void aReplyWithTheWrongNumberOfVectorsIsRejected() {
        replies.add(Reply.json("{\"data\":[" + item(0, 0) + "]}"));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a", "b")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR));
    }

    @Test
    void aVectorOfTheWrongLengthIsRejected() {
        replies.add(Reply.json("{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}]}"));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR);
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void aVectorThatHoldsAnythingButNumbersIsRejected() {
        for (var embedding : List.of("[0.1,0.2,0.3,\"0.4\"]", "[0.1,0.2,0.3,null]", "[0.1,0.2,0.3,1e999]",
                "[0.1,0.2,0.3,[1]]", "\"0.1,0.2,0.3,0.4\"", "null")) {
            replies.add(Reply.json("{\"data\":[{\"embedding\":" + embedding + ",\"index\":0}]}"));

            assertThatThrownBy(() -> client().embedDocuments(List.of("a"))).as(embedding)
                    .isInstanceOfSatisfying(LlmException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR));
        }
    }

    @Test
    void twoVectorsWithOneIndexAreRejected() {
        replies.add(Reply.json("{\"data\":[" + item(0, 0) + "," + item(0, 1) + "]}"));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a", "b")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR));
    }

    @Test
    void anIndexOutsideTheRequestIsRejected() {
        replies.add(Reply.json("{\"data\":[" + item(5, 0) + "]}"));

        assertThatThrownBy(() -> client().embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR));
    }

    @Test
    void aServerThatDoesNotAnswerInTimeIsCutOffAndRetried() {
        replies.add(Reply.ok().after(Duration.ofSeconds(5)));

        var vectors = client(1, 3).embedDocuments(List.of("a"));

        assertThat(vectors).containsExactly(vector(0));
        assertThat(received).hasSize(2);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void aServerThatNeverAnswersEndsInATimeoutFailure() {
        for (int i = 0; i < 4; i++) {
            replies.add(Reply.ok().after(Duration.ofSeconds(3)));
        }

        assertThatThrownBy(() -> client(1, 0).embedDocuments(List.of("a")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_TIMEOUT));
    }

    @Test
    void anUnreachableServerIsRetriedAndThenReported() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        var unreachable = new VoyageEmbeddingClient(props(closedPort, 5, 2, API_KEY), JSON,
                new RetryPolicy(2, Duration.ofSeconds(1), sleeps::add, () -> 0.5));

        assertThatThrownBy(() -> unreachable.embedQuery("question"))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.LLM_ERROR);
                    assertThat(e.toString()).doesNotContain(API_KEY);
                });

        assertThat(sleeps).hasSize(2);
    }

    @Test
    void theClientBuiltFromTheConfigurationRetriesAsOftenAsConfigured() {
        for (int i = 0; i < 3; i++) {
            replies.add(Reply.status(503));
        }
        var configured = new VoyageEmbeddingClient(props(server.getAddress().getPort(), 5, 1, API_KEY), JSON);

        assertThatThrownBy(() -> configured.embedDocuments(List.of("a"))).isInstanceOf(LlmException.class);

        assertThat(received).hasSize(2);
    }

    @Test
    void reportsItsModelAndDimensionsAndIsEnabled() {
        var client = client();

        assertThat(client.enabled()).isTrue();
        assertThat(client.model()).isEqualTo("test-model");
        assertThat(client.dimensions()).isEqualTo(DIMENSIONS);
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new VoyageEmbeddingClient(props(1, 5, 3, "  "), JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VOYAGE_API_KEY")
                .hasMessageContaining("EMBEDDING_PROVIDER=voyage");
    }
}
