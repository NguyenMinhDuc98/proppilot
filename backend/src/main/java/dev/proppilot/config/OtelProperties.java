package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenTelemetry tracing. Off by default: nothing is created and nothing leaves the process. When on, the exporter reads
 * the standard variables ({@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code OTEL_EXPORTER_OTLP_HEADERS},
 * {@code OTEL_SERVICE_NAME} and so on). A host may pass the flag, the endpoint and the service name on empty, which
 * counts as not set. The exporter refuses an empty value of the others, and tracing then exports nothing.
 *
 * <p>The endpoints are bound only to know whether the exporter has one to send to. Each is the system property of the
 * exporter's name or the variable of that name, whichever is set, as the exporter itself resolves it.
 *
 * @param enabled {@code OTEL_ENABLED}; absent or empty is off
 * @param endpoint {@code OTEL_EXPORTER_OTLP_ENDPOINT}: where spans go, with the other signals
 * @param tracesEndpoint {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}: where spans go, when they are to go somewhere other
 *                       than the other signals
 */
@ConfigurationProperties(prefix = "proppilot.otel")
public record OtelProperties(Boolean enabled, String endpoint, String tracesEndpoint) {

    public OtelProperties {
        enabled = Boolean.TRUE.equals(enabled);
        endpoint = endpoint == null ? "" : endpoint.strip();
        tracesEndpoint = tracesEndpoint == null ? "" : tracesEndpoint.strip();
    }

    /** Whether there is somewhere to send spans to. Tracing that is on without it exports nothing. */
    public boolean hasEndpoint() {
        return !endpoint.isEmpty() || !tracesEndpoint.isEmpty();
    }
}
