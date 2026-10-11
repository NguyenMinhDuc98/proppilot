package dev.proppilot.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.config.OtelProperties;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Which tracer the application gets: OpenTelemetry only when it is switched on, nothing created otherwise. */
class TracingConfigTest {

    @Configuration
    @EnableConfigurationProperties(OtelProperties.class)
    static class Properties {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, TracingConfig.class);

    @Test
    void isOffWhenNothingIsConfigured() {
        context.run(started -> {
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
            assertThat(started.getBeansOfType(OpenTelemetry.class)).isEmpty();
        });
    }

    @Test
    void isOffWhenTheFlagIsFalse() {
        context.withPropertyValues("proppilot.otel.enabled=false").run(started ->
                assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class));
    }

    @Test
    void isOffWhenTheHostPassesTheFlagOnEmpty() {
        context.withPropertyValues("proppilot.otel.enabled=").run(started ->
                assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class));
    }

    @Test
    void isOnOnlyWhenTheFlagIsTrue() {
        context.withPropertyValues("proppilot.otel.enabled=true").run(started ->
                assertThat(started.getBean(RunTracer.class)).isInstanceOf(OpenTelemetryRunTracer.class));
    }

    @Test
    void theOffTracerRecordsNothingAndDoesNotFail() {
        new NoopRunTracer().record(null);
    }
}
