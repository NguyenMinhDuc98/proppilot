package dev.proppilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ContextConfiguration;

/**
 * The whole application, started on a host that passes every numeric setting of the LLM client as only spaces. The
 * numbers are the ones that Spring does not read as absent by itself, so this fails if the converter that does is not
 * part of the application.
 */
@ContextConfiguration(initializers = HostWhitespaceIT.SpacesForEveryNumber.class)
class HostWhitespaceIT extends PostgresIntegrationTest {

    static class SpacesForEveryNumber implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final List<String> NUMBERS = List.of("LLM_PRICE_INPUT_PER_MTOK", "LLM_PRICE_OUTPUT_PER_MTOK",
                "LLM_CACHE_WRITE_MULTIPLIER", "LLM_CACHE_READ_MULTIPLIER", "LLM_LARGE_PRICE_INPUT_PER_MTOK",
                "LLM_LARGE_PRICE_OUTPUT_PER_MTOK", "LLM_COMPLEXITY_THRESHOLD");

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var host = new HashMap<String, Object>();
            NUMBERS.forEach(variable -> host.put(variable, "   "));
            context.getEnvironment().getPropertySources()
                    .addFirst(new SystemEnvironmentPropertySource("host", host));
        }
    }

    @Autowired
    LlmProperties props;

    @Test
    void startsOnTheDefaultsOfEveryNumber() {
        assertThat(props.priceInputPerMtok()).isEqualByComparingTo("1.00");
        assertThat(props.priceOutputPerMtok()).isEqualByComparingTo("5.00");
        assertThat(props.cacheWriteMultiplier()).isEqualByComparingTo("1.25");
        assertThat(props.cacheReadMultiplier()).isEqualByComparingTo("0.10");
        assertThat(props.largePriceInputPerMtok()).isEqualByComparingTo("3.00");
        assertThat(props.largePriceOutputPerMtok()).isEqualByComparingTo("15.00");
        assertThat(props.complexityThreshold()).isEqualTo(3);
    }
}
