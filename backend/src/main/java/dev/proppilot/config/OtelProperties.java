package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenTelemetry tracing. Off by default: nothing is created and nothing leaves the process. When on, the exporter reads
 * the standard variables ({@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code OTEL_EXPORTER_OTLP_HEADERS},
 * {@code OTEL_SERVICE_NAME} and so on). A host may pass any of them on empty, which counts as not set.
 *
 * @param enabled {@code OTEL_ENABLED}; absent or empty is off
 * @param endpoint {@code OTEL_EXPORTER_OTLP_ENDPOINT}: where spans go. Tracing that is on without it exports nothing
 */
@ConfigurationProperties(prefix = "proppilot.otel")
public record OtelProperties(Boolean enabled, String endpoint) {

    public OtelProperties {
        enabled = Boolean.TRUE.equals(enabled);
        endpoint = endpoint == null ? "" : endpoint.strip();
    }

    /** Whether there is somewhere to send spans to. */
    public boolean hasEndpoint() {
        return !endpoint.isEmpty();
    }
}
