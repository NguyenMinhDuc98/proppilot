package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.proppilot.agent.llm.AnthropicLlmClient;
import dev.proppilot.agent.routing.ComplexityAssessor;
import dev.proppilot.agent.routing.ModelRouter;
import dev.proppilot.agent.routing.RoutedReason;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.config.AgentProperties;
import dev.proppilot.config.TestLlmProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The agent loop with the real Claude client against a server whose default model stalls. The client's own retries of
 * a timeout would use up the run's time on the model that stalls, and the larger model would never be asked.
 */
class StalledModelFallbackTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";
    private static final Duration STALL = Duration.ofSeconds(30);
    private static final String ANSWER_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Three tenants."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private final List<String> modelsAsked = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/v1/messages", exchange -> {
            var model = JSON.readTree(exchange.getRequestBody().readAllBytes()).get("model").asText();
            modelsAsked.add(model);
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            boolean stalls = model.equals(SMALL);
            var body = stalls ? new byte[0] : ANSWER_STREAM.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, stalls ? 0 : body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
                if (stalls) {
                    out.flush();
                    Thread.sleep(STALL);
                }
            } catch (IOException | InterruptedException e) {
                // The client gave up on the stalled stream, as it should.
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void aDefaultModelThatStallsIsNotAskedAgainAndTheLargerModelAnswersWithinTheRun() {
        var props = TestLlmProperties.defaults().model(SMALL).largeModel(LARGE)
                .apiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages")
                .timeoutSeconds(1).retries(3, 10).build();
        var client = new AnthropicLlmClient(props, JSON);
        var router = new ModelRouter(client, props, new ComplexityAssessor());
        var registry = new ToolRegistry(List.of(), mock(PlatformTransactionManager.class));
        var loop = new AgentLoop(client, registry, new AgentProperties(6, 10, 10), Clock.systemUTC(), router);
        var request = AgentRequest.question("Who is more than 30 days late on rent?", List.of(), UUID.randomUUID());

        var result = loop.run(request, event -> { }, () -> false);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo("Three tenants.");
        assertThat(modelsAsked).containsExactly(SMALL, LARGE);
        assertThat(result.route().model()).isEqualTo(LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.FALLBACK);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::errorCode)
                .containsExactly(ErrorCode.LLM_TIMEOUT, null);
    }
}
