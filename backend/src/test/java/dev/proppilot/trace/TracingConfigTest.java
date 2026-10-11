package dev.proppilot.trace;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.proppilot.config.OtelProperties;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Which tracer the application gets: OpenTelemetry only when it is switched on and has an endpoint to send to, nothing
 * created otherwise, and in no case a backend that does not start.
 */
class TracingConfigTest {

    @Configuration
    @EnableConfigurationProperties(OtelProperties.class)
    static class Properties {
    }

    private static final String ENDPOINT = "proppilot.otel.endpoint=http://collector.example:4318";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, TracingConfig.class);

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger configLogger = (Logger) LoggerFactory.getLogger(TracingConfig.class);

    @BeforeEach
    void captureLog() {
        log.start();
        configLogger.addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        configLogger.detachAppender(log);
    }

    @Test
    void isOffWhenNothingIsConfigured() {
        context.run(started -> {
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
            assertThat(started.getBeansOfType(OpenTelemetry.class)).isEmpty();
        });
        assertThat(log.list).isEmpty();
    }

    @Test
    void isOffWhenTheFlagIsFalse() {
        context.withPropertyValues("proppilot.otel.enabled=false", ENDPOINT).run(started ->
                assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class));
    }

    @Test
    void isOffWhenTheHostPassesTheFlagOnEmpty() {
        context.withPropertyValues("proppilot.otel.enabled=", ENDPOINT).run(started ->
                assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class));
    }

    @Test
    void isOnWhenTheFlagIsTrueAndThereIsAnEndpoint() {
        context.withPropertyValues("proppilot.otel.enabled=true", ENDPOINT).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(OpenTelemetryRunTracer.class);
        });
        assertThat(log.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void startsAndExportsNothingWhenTheFlagIsTrueAndTheEndpointIsBlank(String blank) {
        context.withPropertyValues("proppilot.otel.enabled=true", "proppilot.otel.endpoint=" + blank).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
            assertThat(started.getBeansOfType(OpenTelemetry.class)).isEmpty();
        });
        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("OTEL_EXPORTER_OTLP_ENDPOINT");
        });
    }

    @Test
    void startsAndExportsNothingWhenTheFlagIsTrueAndThereIsNoEndpoint() {
        context.withPropertyValues("proppilot.otel.enabled=true").run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
        });
    }

    @Test
    void theOffTracerRecordsNothingAndDoesNotFail() {
        new NoopRunTracer().record(null);
    }
}
