package dev.proppilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot's own OpenTelemetry auto-configuration is excluded: the OpenTelemetry SDK is on the classpath for
 * {@link dev.proppilot.trace.OpenTelemetryRunTracer}, and with it Boot would build an SDK (and start a log processor
 * thread) at every start, even with {@code OTEL_ENABLED} off, and a second one beside the tracer's when it is on.
 */
@SpringBootApplication(excludeName = {
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration"})
@ConfigurationPropertiesScan
public class ProppilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProppilotApplication.class, args);
    }
}
