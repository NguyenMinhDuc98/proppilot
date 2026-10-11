package dev.proppilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.guardrail.GuardrailMode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * A host may pass a variable on empty or as only spaces: a dashboard field left blank, a workflow input that was not
 * given. For each setting of the model routing, the prompt cache, the guardrail and OpenTelemetry, a blank value must
 * read as an absent one, so it can neither stop the backend nor leave a price empty. The context is configured by the
 * real application.yml and the variables are given the way a host gives them, as the process environment, which keeps
 * the spaces (a test property value is trimmed before the application sees it).
 */
class BlankHostValuesTest {

    @Configuration
    @EnableConfigurationProperties({LlmProperties.class, GuardrailProperties.class, OtelProperties.class})
    @Import(BlankToDefaultConverter.class)
    static class Beans {
    }

    private static final List<String> VARIABLES = List.of("LLM_PRICE_INPUT_PER_MTOK", "LLM_PRICE_OUTPUT_PER_MTOK",
            "PROMPT_CACHING", "ANTHROPIC_MODEL_LARGE", "LLM_LARGE_PRICE_INPUT_PER_MTOK",
            "LLM_LARGE_PRICE_OUTPUT_PER_MTOK", "LLM_CACHE_WRITE_MULTIPLIER", "LLM_CACHE_READ_MULTIPLIER",
            "LLM_COMPLEXITY_THRESHOLD", "GUARDRAIL_MODE", "OTEL_ENABLED", "OTEL_EXPORTER_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");

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

    private ApplicationContextRunner onAHostThatSets(Map<String, String> variables) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("host", new HashMap<String, Object>(variables))));
    }

    private Settings settingsWith(Map<String, String> variables) {
        var read = new Settings[1];
        onAHostThatSets(variables).run(context -> read[0] = Settings.of(context));
        return read[0];
    }

    @Test
    void theDocumentedDefaultsApplyWhenNothingIsSet() {
        var settings = settingsWith(Map.of());

        assertThat(settings.llm().priceInputPerMtok()).isEqualByComparingTo("1.00");
        assertThat(settings.llm().priceOutputPerMtok()).isEqualByComparingTo("5.00");
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
    @ValueSource(strings = {"", "   ", " \t "})
    void aBlankVariableReadsAsAnAbsentOne(String blank) {
        var absent = settingsWith(Map.of());

        for (var variable : VARIABLES) {
            var settings = settingsWith(Map.of(variable, blank));

            assertThat(settings).as("%s=\"%s\"", variable, blank.replace("\t", "\\t"))
                    .usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(absent);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void everyVariableBlankAtOnceStartsTheBackendOnItsDefaults(String blank) {
        var everyBlank = new HashMap<String, String>();
        VARIABLES.forEach(variable -> everyBlank.put(variable, blank));

        assertThat(settingsWith(everyBlank)).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(settingsWith(Map.of()));
    }

    @Test
    void aSettingThatIsGivenIsUsed() {
        var settings = settingsWith(Map.of(
                "LLM_PRICE_INPUT_PER_MTOK", "0.80",
                "LLM_PRICE_OUTPUT_PER_MTOK", "4",
                "ANTHROPIC_MODEL_LARGE", "claude-sonnet-4-5",
                "LLM_LARGE_PRICE_INPUT_PER_MTOK", "6.5",
                "LLM_CACHE_READ_MULTIPLIER", "0.2",
                "PROMPT_CACHING", "false",
                "LLM_COMPLEXITY_THRESHOLD", "5",
                "GUARDRAIL_MODE", "retry",
                "OTEL_ENABLED", "true"));

        assertThat(settings.llm().priceInputPerMtok()).isEqualByComparingTo("0.80");
        assertThat(settings.llm().priceOutputPerMtok()).isEqualByComparingTo("4");
        assertThat(settings.llm().hasLargeModel()).isTrue();
        assertThat(settings.llm().largeModel()).isEqualTo("claude-sonnet-4-5");
        assertThat(settings.llm().largePriceInputPerMtok()).isEqualByComparingTo("6.5");
        assertThat(settings.llm().largePriceOutputPerMtok()).isEqualByComparingTo("15.00");
        assertThat(settings.llm().cacheReadMultiplier()).isEqualByComparingTo("0.2");
        assertThat(settings.llm().promptCaching()).isFalse();
        assertThat(settings.llm().complexityThreshold()).isEqualTo(5);
        assertThat(settings.guardrail()).isEqualTo(GuardrailMode.RETRY);
        assertThat(settings.otel().enabled()).isTrue();
    }

    @Test
    void aNumberWithSpacesAroundItIsStillThatNumber() {
        var settings = settingsWith(Map.of("LLM_COMPLEXITY_THRESHOLD", " 4 ", "LLM_PRICE_INPUT_PER_MTOK", " 2.5 "));

        assertThat(settings.llm().complexityThreshold()).isEqualTo(4);
        assertThat(settings.llm().priceInputPerMtok()).isEqualByComparingTo("2.5");
    }

    @Test
    void aValueThatIsNotANumberStillStopsTheBackend() {
        onAHostThatSets(Map.of("LLM_COMPLEXITY_THRESHOLD", "three")).run(context -> assertThat(context).hasFailed());
        onAHostThatSets(Map.of("LLM_PRICE_INPUT_PER_MTOK", "cheap")).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"})
    void anEndpointGivenAsEitherVariableIsFound(String variable) {
        assertThat(settingsWith(Map.of(variable, "http://collector:4318")).otel().hasEndpoint()).isTrue();
    }

    @Test
    void anEndpointGivenAsTheSystemPropertyOfTheExportersNameIsFoundToo() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("jvm", Map.of("otel.exporter.otlp.endpoint", "http://collector:4318"))))
                .run(context -> assertThat(Settings.of(context).otel().hasEndpoint()).isTrue());
    }
}
