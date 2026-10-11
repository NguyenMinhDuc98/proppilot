package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenTelemetry tracing. Off by default: nothing is created and nothing leaves the process. When on, the exporter reads
 * the standard variables ({@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code OTEL_EXPORTER_OTLP_HEADERS},
 * {@code OTEL_SERVICE_NAME} and so on); the service name is {@code proppilot} unless it says otherwise.
 *
 * @param enabled {@code OTEL_ENABLED}; a value that the host passes on empty counts as off
 */
@ConfigurationProperties(prefix = "proppilot.otel")
public record OtelProperties(Boolean enabled) {

    public OtelProperties {
        enabled = Boolean.TRUE.equals(enabled);
    }
}
