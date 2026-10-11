package dev.proppilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.guardrail.GuardrailMode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * A host may pass a variable on empty: a dashboard field left blank, a workflow input that was not given. For each
 * setting of the model routing, the prompt cache, the guardrail and OpenTelemetry, a blank value must read as an absent
 * one, so it can neither stop the backend nor leave a price empty. The context is configured by the real
 * application.yml and the variable names are the ones a host sets.
 */
class BlankHostValuesTest {

    @Configuration
    @EnableConfigurationProperties({LlmProperties.class, GuardrailProperties.class, OtelProperties.class})
    static class Beans {
    }

    private static final List<String> VARIABLES = List.of("PROMPT_CACHING", "ANTHROPIC_MODEL_LARGE",
            "LLM_LARGE_PRICE_INPUT_PER_MTOK", "LLM_LARGE_PRICE_OUTPUT_PER_MTOK", "LLM_CACHE_WRITE_MULTIPLIER",
            "LLM_CACHE_READ_MULTIPLIER", "LLM_COMPLEXITY_THRESHOLD", "GUARDRAIL_MODE", "OTEL_ENABLED",
            "OTEL_EXPORTER_OTLP_ENDPOINT");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Beans.class);

    private record Settings(LlmProperties llm, GuardrailMode guardrail, OtelProperties otel) {

        static Settings of(AssertableApplicationContext context) {
            assertThat(context).hasNotFailed();
            return new Settings(context.getBean(LlmProperties.class),
                    context.getBean(GuardrailProperties.class).guardrailMode(), context.getBean(OtelProperties.class));
        }
    }

    private Settings settingsWith(String... properties) {
        var read = new Settings[1];
        runner.withPropertyValues(properties).run(context -> read[0] = Settings.of(context));
        return read[0];
    }

    @Test
    void theDocumentedDefaultsApplyWhenNothingIsSet() {
        var settings = settingsWith();

        assertThat(settings.llm().promptCaching()).isTrue();
        assertThat(settings.llm().hasLargeModel()).isFalse();
        assertThat(settings.llm().largePriceInputPerMtok()).isEqualByComparingTo("3.00");
        assertThat(settings.llm().largePriceOutputPerMtok()).isEqualByComparingTo("15.00");
        assertThat(settings.llm().cacheWriteMultiplier()).isEqualByComparingTo("1.25");
        assertThat(settings.llm().cacheReadMultiplier()).isEqualByComparingTo("0.10");
        assertThat(settings.llm().complexityThreshold()).isEqualTo(3);
        assertThat(settings.guardrail()).isEqualTo(GuardrailMode.WARN);
        assertThat(settings.otel().enabled()).isFalse();
        assertThat(settings.otel().hasEndpoint()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankVariableReadsAsAnAbsentOne(String blank) {
        var absent = settingsWith();

        for (var variable : VARIABLES) {
            var settings = settingsWith(variable + "=" + blank);

            assertThat(settings).as("%s=\"%s\"", variable, blank)
                    .usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(absent);
        }
    }

    @Test
    void everyVariableBlankAtOnceStartsTheBackendOnItsDefaults() {
        var everyBlank = VARIABLES.stream().map(variable -> variable + "=").toArray(String[]::new);

        assertThat(settingsWith(everyBlank)).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(settingsWith());
    }

    @Test
    void aSettingThatIsGivenIsUsed() {
        var settings = settingsWith("ANTHROPIC_MODEL_LARGE=claude-sonnet-4-5", "PROMPT_CACHING=false",
                "LLM_COMPLEXITY_THRESHOLD=5", "GUARDRAIL_MODE=retry", "OTEL_ENABLED=true",
                "OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318");

        assertThat(settings.llm().hasLargeModel()).isTrue();
        assertThat(settings.llm().largeModel()).isEqualTo("claude-sonnet-4-5");
        assertThat(settings.llm().promptCaching()).isFalse();
        assertThat(settings.llm().complexityThreshold()).isEqualTo(5);
        assertThat(settings.guardrail()).isEqualTo(GuardrailMode.RETRY);
        assertThat(settings.otel().enabled()).isTrue();
        assertThat(settings.otel().hasEndpoint()).isTrue();
    }
}
