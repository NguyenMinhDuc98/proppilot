package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.config.GuardrailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The application context must not come up with a guardrail mode that nobody can explain. */
class GuardrailStartupTest {

    @Configuration
    @EnableConfigurationProperties(GuardrailProperties.class)
    static class Beans {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Beans.class);

    private static GuardrailMode modeOf(org.springframework.context.ApplicationContext context) {
        return context.getBean(GuardrailProperties.class).guardrailMode();
    }

    @Test
    void startsInWarnWhenNothingIsSet() {
        runner.run(context -> assertThat(modeOf(context)).isEqualTo(GuardrailMode.WARN));
    }

    @Test
    void startsInWarnWhenTheHostPassesTheVariableOnEmpty() {
        runner.withPropertyValues("proppilot.guardrail.mode=").run(context ->
                assertThat(modeOf(context)).isEqualTo(GuardrailMode.WARN));
    }

    @Test
    void startsInTheModeThatIsSet() {
        runner.withPropertyValues("proppilot.guardrail.mode=retry").run(context ->
                assertThat(modeOf(context)).isEqualTo(GuardrailMode.RETRY));
        runner.withPropertyValues("proppilot.guardrail.mode=OFF").run(context ->
                assertThat(modeOf(context)).isEqualTo(GuardrailMode.OFF));
    }

    @Test
    void failsFastWithAClearMessageForAnyOtherMode() {
        runner.withPropertyValues("proppilot.guardrail.mode=strict").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("GUARDRAIL_MODE must be off, warn or retry"));
    }
}
