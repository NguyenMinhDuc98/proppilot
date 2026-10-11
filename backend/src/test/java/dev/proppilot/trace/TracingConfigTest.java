package dev.proppilot.trace;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.proppilot.config.OtelProperties;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

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
    private static final String SDK_ENDPOINT = "otel.exporter.otlp.endpoint";
    private static final String SDK_PROTOCOL = "otel.exporter.otlp.protocol";

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
        System.clearProperty(SDK_ENDPOINT);
        System.clearProperty(SDK_PROTOCOL);
    }

    /** A value as the application receives it from a host, spaces kept: a test property value is trimmed. */
    private ApplicationContextRunner withHostValues(Map<String, Object> values) {
        return context.withInitializer(started -> started.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("host", values)));
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

    @Test
    void isOnWhenTheOnlyEndpointIsTheOneForTraces() {
        context.withPropertyValues("proppilot.otel.enabled=true",
                "proppilot.otel.traces-endpoint=http://collector.example:4318/v1/traces").run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started.getBean(RunTracer.class)).isInstanceOf(OpenTelemetryRunTracer.class);
                });
        assertThat(log.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void startsAndExportsNothingWhenTheFlagIsTrueAndTheEndpointIsBlank(String blank) {
        withHostValues(Map.of("proppilot.otel.enabled", "true", "proppilot.otel.endpoint", blank,
                "proppilot.otel.traces-endpoint", blank)).run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
                    assertThat(started.getBeansOfType(OpenTelemetry.class)).isEmpty();
                });
        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage())
                    .contains("OTEL_EXPORTER_OTLP_ENDPOINT").contains("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
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
    void startsAndExportsNothingWhenTheSdkRefusesTheEndpointAndDoesNotLogIt() {
        // The collector's service name without a scheme, with a token in front of it as a hosted collector gives one
        var endpoint = "t0ken-4f2a@otel-collector:4318";
        System.setProperty(SDK_ENDPOINT, endpoint);

        context.withPropertyValues("proppilot.otel.enabled=true", "proppilot.otel.endpoint=" + endpoint).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
            assertThat(started.getBeansOfType(OpenTelemetry.class)).isEmpty();
        });
        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("ConfigurationException").contains("http://")
                    .doesNotContain("t0ken-4f2a").doesNotContain("otel-collector");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void startsAndExportsNothingWhenAnotherVariableIsPassedOnEmpty() {
        System.setProperty(SDK_ENDPOINT, "http://collector.example:4318");
        System.setProperty(SDK_PROTOCOL, "");

        context.withPropertyValues("proppilot.otel.enabled=true", ENDPOINT).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
        });
        assertThat(log.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("could not be set up"));
    }

    @Test
    void theOffTracerRecordsNothingAndDoesNotFail() {
        new NoopRunTracer().record(null);
    }
}
