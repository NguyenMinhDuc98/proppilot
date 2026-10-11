package dev.proppilot.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The whole application with OpenTelemetry off. The SDK is on the classpath for the tracer, and Spring Boot builds an
 * SDK of its own wherever one is on the classpath, so this is what shows that nothing of it is created.
 */
class OpenTelemetryOffIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theContextHoldsNoOpenTelemetryBean() {
        assertThat(context.getBeansOfType(OpenTelemetry.class)).isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> isOpenTelemetry(context.getType(name))))
                .isEmpty();
    }

    @Test
    void theTracerIsTheOneThatDoesNothing() {
        assertThat(context.getBean(RunTracer.class)).isInstanceOf(NoopRunTracer.class);
    }

    private static boolean isOpenTelemetry(Class<?> type) {
        return type != null && type.getName().toLowerCase(Locale.ROOT).contains("opentelemetry");
    }
}
