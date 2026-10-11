package dev.proppilot.trace;

import dev.proppilot.config.OtelProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TracingConfig {

    /** The OpenTelemetry classes are not touched unless OpenTelemetry is on. */
    @Bean
    RunTracer runTracer(OtelProperties props) {
        return props.enabled() ? OpenTelemetryRunTracer.fromEnvironment() : new NoopRunTracer();
    }
}
