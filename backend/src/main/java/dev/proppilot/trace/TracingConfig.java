package dev.proppilot.trace;

import dev.proppilot.config.OtelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    /**
     * The OpenTelemetry classes are not touched unless spans are to be exported: OpenTelemetry is on and there is an
     * endpoint to send them to. Without one the SDK would only send them to its own default, which is nowhere on a
     * host, so the backend starts, says so and exports nothing.
     */
    @Bean
    RunTracer runTracer(OtelProperties props) {
        if (!props.enabled()) {
            return new NoopRunTracer();
        }
        if (!props.hasEndpoint()) {
            log.warn("OTEL_ENABLED is true but OTEL_EXPORTER_OTLP_ENDPOINT is not set: no spans are exported");
            return new NoopRunTracer();
        }
        return OpenTelemetryRunTracer.fromEnvironment();
    }
}
