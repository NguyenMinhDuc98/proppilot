package dev.proppilot.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.llm.StopReason;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tracer the application builds when OpenTelemetry is on, against a local stand-in for a collector: it must send
 * the run to the configured endpoint over OTLP/HTTP, named {@code proppilot}, and nowhere else.
 */
class OpenTelemetryExportTest {

    private record Received(String path, String contentType, String body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer collector;

    @BeforeEach
    void startCollector() throws IOException {
        collector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        collector.createContext("/", exchange -> {
            var body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(body, StandardCharsets.ISO_8859_1)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
        System.setProperty("otel.exporter.otlp.endpoint", "http://127.0.0.1:" + collector.getAddress().getPort());
    }

    @AfterEach
    void stopCollector() {
        System.clearProperty("otel.exporter.otlp.endpoint");
        collector.stop(0);
    }

    @Test
    void sendsARunToTheConfiguredEndpointAsOtlpOverHttp() {
        var at = Instant.parse("2026-10-01T08:00:00Z");
        var call = new LlmCallRecord(1, "claude-haiku-4-5-20251001", 100, 20, 0, 0, 500, StopReason.END_TURN,
                LlmCallRecord.Outcome.OK, null, at);
        var run = new TracedRun(UUID.randomUUID(), "anthropic", "claude-haiku-4-5-20251001", AgentResult.Status.OK, null,
                null, null, 1, 0, at, 600, List.of(call), List.of());

        try (var tracer = OpenTelemetryRunTracer.fromEnvironment()) {
            tracer.record(run);
        }

        assertThat(received).hasSize(1);
        var export = received.get(0);
        assertThat(export.path()).isEqualTo("/v1/traces");
        assertThat(export.contentType()).isEqualTo("application/x-protobuf");
        assertThat(export.body()).contains("service.name").contains("proppilot").contains("proppilot.run")
                .contains(run.runId().toString());
    }
}
