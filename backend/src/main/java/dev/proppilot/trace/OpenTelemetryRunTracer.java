package dev.proppilot.trace;

import dev.proppilot.agent.LlmCallRecord;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a run as spans: one root span for the run, and a child for each model call and each tool execution, placed at
 * the times that were measured. A span holds only identifiers, names, counts, timings and outcomes: never a prompt, an
 * answer, a question, a tool's arguments or result, or a key.
 */
public final class OpenTelemetryRunTracer implements RunTracer, AutoCloseable {

    static final String SPAN_RUN = "proppilot.run";
    static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("run.id");
    static final AttributeKey<String> RUN_STATUS = AttributeKey.stringKey("run.status");
    static final AttributeKey<String> RUN_MODEL = AttributeKey.stringKey("run.model");
    static final AttributeKey<String> RUN_ROUTED_REASON = AttributeKey.stringKey("run.routed_reason");
    static final AttributeKey<String> RUN_GUARDRAIL_STATUS = AttributeKey.stringKey("run.guardrail_status");
    static final AttributeKey<Long> RUN_TOOL_CALLS = AttributeKey.longKey("run.tool_calls");
    static final AttributeKey<Long> RUN_ITERATIONS = AttributeKey.longKey("run.iterations");

    static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    static final AttributeKey<Long> GEN_AI_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    static final AttributeKey<Long> GEN_AI_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    static final AttributeKey<Long> GEN_AI_CACHE_READ_TOKENS = AttributeKey.longKey("gen_ai.usage.cache_read_input_tokens");
    static final AttributeKey<Long> GEN_AI_CACHE_WRITE_TOKENS =
            AttributeKey.longKey("gen_ai.usage.cache_creation_input_tokens");
    static final AttributeKey<List<String>> GEN_AI_FINISH_REASONS = AttributeKey.stringArrayKey("gen_ai.response.finish_reasons");
    static final AttributeKey<String> LLM_OUTCOME = AttributeKey.stringKey("llm.outcome");
    static final AttributeKey<String> LLM_ERROR_CODE = AttributeKey.stringKey("llm.error_code");

    static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("tool.name");
    static final AttributeKey<Boolean> TOOL_ERROR = AttributeKey.booleanKey("tool.error");

    private static final String INSTRUMENTATION = "dev.proppilot";
    private static final String SERVICE_NAME_PROPERTY = "otel.service.name";
    private static final String DEFAULT_SERVICE_NAME = "proppilot";

    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;

    public OpenTelemetryRunTracer(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
        this.tracer = sdk.getTracer(INSTRUMENTATION);
    }

    /**
     * A tracer configured the standard way: from the {@code OTEL_*} environment variables. The service name is
     * {@code proppilot} and the protocol {@code http/protobuf} unless the environment says otherwise; only traces are
     * exported. A service name that a host passed on empty is the default one, not an unnamed service.
     */
    public static OpenTelemetryRunTracer fromEnvironment() {
        var sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.exporter.otlp.protocol", "http/protobuf",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none"))
                .addPropertiesCustomizer(OpenTelemetryRunTracer::serviceNameWhenBlank)
                .disableShutdownHook()
                .build()
                .getOpenTelemetrySdk();
        return new OpenTelemetryRunTracer(sdk);
    }

    private static Map<String, String> serviceNameWhenBlank(ConfigProperties config) {
        var name = config.getString(SERVICE_NAME_PROPERTY);
        return name == null || name.isBlank() ? Map.of(SERVICE_NAME_PROPERTY, DEFAULT_SERVICE_NAME) : Map.of();
    }

    @Override
    public void record(TracedRun run) {
        var root = tracer.spanBuilder(SPAN_RUN)
                .setNoParent()
                .setStartTimestamp(run.startedAt())
                .startSpan();
        root.setAttribute(RUN_ID, run.runId().toString());
        root.setAttribute(RUN_STATUS, run.status().name());
        root.setAttribute(RUN_MODEL, run.model());
        if (run.routedReason() != null) {
            root.setAttribute(RUN_ROUTED_REASON, run.routedReason());
        }
        if (run.guardrailStatus() != null) {
            root.setAttribute(RUN_GUARDRAIL_STATUS, run.guardrailStatus());
        }
        root.setAttribute(RUN_TOOL_CALLS, (long) run.toolCalls());
        root.setAttribute(RUN_ITERATIONS, (long) run.iterations());
        if (run.errorCode() != null) {
            root.setStatus(StatusCode.ERROR, run.errorCode().code());
        }

        var parent = Context.root().with(root);
        run.llmCalls().forEach(call -> recordModelCall(parent, run.provider(), call));
        run.tools().forEach(tool -> recordTool(parent, tool));
        root.end(run.startedAt().plusMillis(run.latencyMs()));
    }

    private void recordModelCall(Context parent, String provider, LlmCallRecord call) {
        var span = tracer.spanBuilder("chat " + call.model())
                .setParent(parent)
                .setSpanKind(SpanKind.CLIENT)
                .setStartTimestamp(call.startedAt())
                .startSpan();
        span.setAttribute(GEN_AI_SYSTEM, provider);
        span.setAttribute(GEN_AI_REQUEST_MODEL, call.model());
        span.setAttribute(GEN_AI_INPUT_TOKENS, (long) call.inputTokens());
        span.setAttribute(GEN_AI_OUTPUT_TOKENS, (long) call.outputTokens());
        span.setAttribute(GEN_AI_CACHE_READ_TOKENS, (long) call.cacheReadTokens());
        span.setAttribute(GEN_AI_CACHE_WRITE_TOKENS, (long) call.cacheWriteTokens());
        if (call.stopReason() != null) {
            span.setAttribute(GEN_AI_FINISH_REASONS, List.of(call.stopReason().name().toLowerCase(Locale.ROOT)));
        }
        span.setAttribute(LLM_OUTCOME, call.outcome().name().toLowerCase(Locale.ROOT));
        if (call.errorCode() != null) {
            span.setAttribute(LLM_ERROR_CODE, call.errorCode().code());
            span.setStatus(StatusCode.ERROR, call.errorCode().code());
        }
        end(span, call.startedAt(), call.latencyMs());
    }

    private void recordTool(Context parent, TracedRun.Tool tool) {
        var span = tracer.spanBuilder("tool " + tool.name())
                .setParent(parent)
                .setStartTimestamp(tool.startedAt())
                .startSpan();
        span.setAttribute(TOOL_NAME, tool.name());
        span.setAttribute(TOOL_ERROR, tool.error());
        if (tool.error()) {
            span.setStatus(StatusCode.ERROR);
        }
        end(span, tool.startedAt(), tool.latencyMs());
    }

    private static void end(Span span, Instant startedAt, long latencyMs) {
        span.end(startedAt.plusMillis(latencyMs));
    }

    /** Flushes the spans that are still waiting to be exported. */
    @Override
    public void close() {
        sdk.close();
    }
}
