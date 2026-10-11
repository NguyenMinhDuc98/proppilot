package dev.proppilot.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.ToolCallRecord;
import dev.proppilot.agent.llm.StopReason;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryRunTracerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String MODEL = "claude-haiku-4-5-20251001";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
            .build();
    private final OpenTelemetryRunTracer tracer = new OpenTelemetryRunTracer(sdk);

    @AfterEach
    void shutDown() {
        tracer.close();
    }

    private static LlmCallRecord okCall(int sequence, Instant startedAt, long latencyMs, StopReason stop) {
        return new LlmCallRecord(sequence, MODEL, 1200, 45, 3000, 400, latencyMs, stop, LlmCallRecord.Outcome.OK, null,
                startedAt);
    }

    private static TracedRun run(AgentResult.Status status, ErrorCode errorCode, List<LlmCallRecord> calls,
                                 List<ToolCallRecord> tools) {
        return new TracedRun(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "anthropic", MODEL, status, errorCode,
                null, null, calls.size(), tools.size(), T0, 2000, calls,
                tools.stream().map(TracedRun.Tool::of).toList());
    }

    private static TracedRun anOrdinaryRun() {
        var first = okCall(1, T0, 640, StopReason.TOOL_USE);
        var second = okCall(2, T0.plusMillis(700), 710, StopReason.END_TURN);
        var tool = new ToolCallRecord(1, 1, "search_units", "{\"city\":\"Riyadh\"}", "3 units found", false, 25,
                T0.plusMillis(650));
        return run(AgentResult.Status.OK, null, List.of(first, second), List.of(tool));
    }

    private SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name + " in " + names()));
    }

    private List<String> names() {
        return exporter.getFinishedSpanItems().stream().map(SpanData::getName).toList();
    }

    private static long nanos(Instant instant) {
        return TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
    }

    @Test
    void exportsOneRootSpanWithAChildForEachModelCallAndEachTool() {
        tracer.record(anOrdinaryRun());

        assertThat(names()).containsExactlyInAnyOrder("proppilot.run", "chat " + MODEL, "chat " + MODEL,
                "tool search_units");
    }

    @Test
    void theRootSpanDescribesTheRunAndSpansItsWholeDuration() {
        tracer.record(anOrdinaryRun());

        var root = span("proppilot.run");
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(root.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(root.getStartEpochNanos()).isEqualTo(nanos(T0));
        assertThat(root.getEndEpochNanos()).isEqualTo(nanos(T0.plusMillis(2000)));
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        var attributes = root.getAttributes();
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_ID)).isEqualTo("00000000-0000-0000-0000-0000000000a1");
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_STATUS)).isEqualTo("OK");
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_MODEL)).isEqualTo(MODEL);
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_TOOL_CALLS)).isEqualTo(1L);
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_ITERATIONS)).isEqualTo(2L);
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_ROUTED_REASON)).isNull();
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_GUARDRAIL_STATUS)).isNull();
    }

    @Test
    void theRootSpanCarriesTheRoutingReasonAndTheGuardrailStatusWhenThereAreOnes() {
        var base = anOrdinaryRun();
        tracer.record(new TracedRun(base.runId(), base.provider(), base.model(), base.status(), null, "complex", "FLAGGED",
                base.iterations(), base.toolCalls(), base.startedAt(), base.latencyMs(), base.llmCalls(), base.tools()));

        var attributes = span("proppilot.run").getAttributes();
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_ROUTED_REASON)).isEqualTo("complex");
        assertThat(attributes.get(OpenTelemetryRunTracer.RUN_GUARDRAIL_STATUS)).isEqualTo("FLAGGED");
    }

    @Test
    void everyChildHasTheRootAsItsParentInTheSameTrace() {
        tracer.record(anOrdinaryRun());

        var root = span("proppilot.run");
        var children = exporter.getFinishedSpanItems().stream().filter(span -> span != root).toList();
        assertThat(children).hasSize(3).allSatisfy(child -> {
            assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(child.getTraceId()).isEqualTo(root.getTraceId());
        });
    }

    @Test
    void aModelCallSpanHasTheUsageTheModelAndTheStopReason() {
        tracer.record(anOrdinaryRun());

        var calls = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().startsWith("chat ")).toList();
        var first = calls.stream().filter(span -> span.getStartEpochNanos() == nanos(T0)).findFirst().orElseThrow();
        assertThat(first.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(first.getEndEpochNanos()).isEqualTo(nanos(T0.plusMillis(640)));
        var attributes = first.getAttributes();
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_SYSTEM)).isEqualTo("anthropic");
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_REQUEST_MODEL)).isEqualTo(MODEL);
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_INPUT_TOKENS)).isEqualTo(1200L);
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_OUTPUT_TOKENS)).isEqualTo(45L);
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_CACHE_READ_TOKENS)).isEqualTo(3000L);
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_CACHE_WRITE_TOKENS)).isEqualTo(400L);
        assertThat(attributes.get(OpenTelemetryRunTracer.GEN_AI_FINISH_REASONS)).containsExactly("tool_use");
        assertThat(attributes.get(OpenTelemetryRunTracer.LLM_OUTCOME)).isEqualTo("ok");
        assertThat(first.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void theOfflineAssistantIsNamedAsTheSystem() {
        var base = anOrdinaryRun();
        tracer.record(new TracedRun(base.runId(), "offline", "rule-based", base.status(), null, null, null,
                base.iterations(), base.toolCalls(), base.startedAt(), base.latencyMs(), base.llmCalls(), base.tools()));

        var call = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().startsWith("chat ")).findFirst()
                .orElseThrow();
        assertThat(call.getAttributes().get(OpenTelemetryRunTracer.GEN_AI_SYSTEM)).isEqualTo("offline");
    }

    @Test
    void aToolSpanHasTheToolNameTheErrorFlagAndTheTimesItWasMeasuredAt() {
        tracer.record(anOrdinaryRun());

        var tool = span("tool search_units");
        assertThat(tool.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(tool.getStartEpochNanos()).isEqualTo(nanos(T0.plusMillis(650)));
        assertThat(tool.getEndEpochNanos()).isEqualTo(nanos(T0.plusMillis(675)));
        assertThat(tool.getAttributes().get(OpenTelemetryRunTracer.TOOL_NAME)).isEqualTo("search_units");
        assertThat(tool.getAttributes().get(OpenTelemetryRunTracer.TOOL_ERROR)).isFalse();
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void aFailedRunAndItsFailedCallsAreMarkedAsErrorsWithTheCodeOnly() {
        var failed = new LlmCallRecord(1, MODEL, 0, 0, 0, 0, 5200, null, LlmCallRecord.Outcome.ERROR,
                ErrorCode.LLM_OVERLOADED, T0);
        tracer.record(run(AgentResult.Status.ERROR, ErrorCode.LLM_OVERLOADED, List.of(failed), List.of()));

        var root = span("proppilot.run");
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(root.getStatus().getDescription()).isEqualTo("llm_overloaded");
        assertThat(root.getAttributes().get(OpenTelemetryRunTracer.RUN_STATUS)).isEqualTo("ERROR");
        var call = span("chat " + MODEL);
        assertThat(call.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(call.getStatus().getDescription()).isEqualTo("llm_overloaded");
        assertThat(call.getAttributes().get(OpenTelemetryRunTracer.LLM_OUTCOME)).isEqualTo("error");
        assertThat(call.getAttributes().get(OpenTelemetryRunTracer.LLM_ERROR_CODE)).isEqualTo("llm_overloaded");
        assertThat(call.getAttributes().get(OpenTelemetryRunTracer.GEN_AI_FINISH_REASONS)).isNull();
    }

    @Test
    void aToolThatFailedIsMarkedAsAnError() {
        var tool = new ToolCallRecord(1, 1, "get_unit_details", "{}", "Invalid input", true, 3, T0);
        tracer.record(run(AgentResult.Status.OK, null, List.of(okCall(1, T0, 100, StopReason.END_TURN)), List.of(tool)));

        var span = span("tool get_unit_details");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(OpenTelemetryRunTracer.TOOL_ERROR)).isTrue();
        assertThat(span("proppilot.run").getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void aTruncatedRunIsNotAFailure() {
        var cut = new LlmCallRecord(1, MODEL, 40, 2048, 0, 0, 900, StopReason.MAX_TOKENS, LlmCallRecord.Outcome.OK, null, T0);
        tracer.record(run(AgentResult.Status.TRUNCATED, null, List.of(cut), List.of()));

        var root = span("proppilot.run");
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(root.getAttributes().get(OpenTelemetryRunTracer.RUN_STATUS)).isEqualTo("TRUNCATED");
    }

    @Test
    void noSpanHoldsWhatAToolWasAskedOrAnswered() {
        var tool = new ToolCallRecord(1, 1, "get_unit_details", "{\"unit_code\":\"SECRET-ARGUMENT\"}",
                "SECRET-SUMMARY of tenant Ahmed", false, 3, T0);
        tracer.record(run(AgentResult.Status.OK, null, List.of(okCall(1, T0, 100, StopReason.END_TURN)), List.of(tool)));

        var everything = exporter.getFinishedSpanItems().stream()
                .map(span -> span.getName() + " " + span.getAttributes().asMap() + " " + span.getStatus())
                .collect(Collectors.joining("\n"));
        assertThat(everything).doesNotContain("SECRET-ARGUMENT").doesNotContain("SECRET-SUMMARY").doesNotContain("Ahmed");
    }

    @Test
    void spansHoldOnlyTheDocumentedAttributes() {
        tracer.record(anOrdinaryRun());

        var keys = exporter.getFinishedSpanItems().stream()
                .flatMap(span -> span.getAttributes().asMap().keySet().stream())
                .map(key -> key.getKey())
                .collect(Collectors.toSet());
        assertThat(keys).isSubsetOf(Set.of("run.id", "run.status", "run.model", "run.routed_reason",
                "run.guardrail_status", "run.tool_calls", "run.iterations", "gen_ai.system", "gen_ai.request.model",
                "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.usage.cache_read_input_tokens",
                "gen_ai.usage.cache_creation_input_tokens", "gen_ai.response.finish_reasons", "llm.outcome",
                "llm.error_code", "tool.name", "tool.error"));
    }

    @Test
    void aRunWithNoCallsIsJustTheRootSpan() {
        tracer.record(run(AgentResult.Status.ABORTED, null, List.of(), List.of()));

        assertThat(names()).containsExactly("proppilot.run");
    }
}
