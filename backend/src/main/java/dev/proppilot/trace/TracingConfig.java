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
     * host, so the backend starts, says so and exports nothing. The same when the SDK refuses what it is given, an
     * endpoint without a scheme or an empty protocol: telemetry never stops the backend from starting.
     */
    @Bean
    RunTracer runTracer(OtelProperties props) {
        if (!props.enabled()) {
            return new NoopRunTracer();
        }
        if (!props.hasEndpoint()) {
            log.warn("OTEL_ENABLED is true but neither OTEL_EXPORTER_OTLP_ENDPOINT nor "
                    + "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT is set: no spans are exported");
            return new NoopRunTracer();
        }
        try {
            return OpenTelemetryRunTracer.fromEnvironment();
        } catch (RuntimeException e) {
            // The SDK's message quotes the value it refused, and an endpoint can carry a credential: log the kind only
            log.warn("OpenTelemetry could not be set up ({}): no spans are exported. Check the OTEL_* variables: an "
                    + "endpoint needs http:// or https://, and a variable that is set needs a value",
                    e.getClass().getSimpleName());
            return new NoopRunTracer();
        }
    }
}
