package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.LlmProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator(new LlmProperties(
            "anthropic", "k", "m", "u", 1024, 60, 3, 1000, new BigDecimal("1.00"), new BigDecimal("5.00")));

    @Test
    void pricesInputAndOutputTokensPerMillion() {
        var cost = calculator.cost(new Usage(10_000, 2_000), true);

        assertThat(cost).isEqualByComparingTo("0.020000");
    }

    @Test
    void nonBillableProvidersCostNothing() {
        assertThat(calculator.cost(new Usage(10_000, 2_000), false)).isEqualByComparingTo("0");
    }
}
