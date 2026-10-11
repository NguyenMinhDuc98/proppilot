package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.LlmProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private final CostCalculator calculator = calculator("1.00", "5.00", "1.25", "0.10");

    private static CostCalculator calculator(String input, String output, String cacheWrite, String cacheRead) {
        return new CostCalculator(new LlmProperties("anthropic", "k", "m", "u", 1024, 60, 3, 1000,
                new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheWrite), new BigDecimal(cacheRead)));
    }

    @Test
    void pricesInputAndOutputTokensPerMillion() {
        var cost = calculator.cost(new Usage(10_000, 2_000), true);

        assertThat(cost).isEqualByComparingTo("0.020000");
    }

    @Test
    void pricesCacheWritesAndReadsAtTheirMultiplesOfTheInputPrice() {
        // 10,000 x 1.00 + 20,000 x 1.25 + 100,000 x 0.10 + 2,000 x 5.00 = 55,000 per million
        var cost = calculator.cost(new Usage(10_000, 2_000, 100_000, 20_000), true);

        assertThat(cost).isEqualByComparingTo("0.055000");
    }

    @Test
    void aRunThatUsedTheCacheCostsMoreThanTheSameRunWithoutIt() {
        var withoutCache = calculator.cost(new Usage(30, 12), true);
        var withCache = calculator.cost(new Usage(30, 12, 4_000, 250), true);

        assertThat(withCache).isGreaterThan(withoutCache);
        assertThat(withCache).isEqualByComparingTo("0.000803");
    }

    @Test
    void theCacheMultiplesFollowTheInputPrice() {
        var larger = calculator("5.00", "25.00", "1.25", "0.10");

        var cost = larger.cost(new Usage(0, 0, 1_000_000, 1_000_000), true);

        assertThat(cost).isEqualByComparingTo("6.750000");
    }

    @Test
    void nonBillableProvidersCostNothing() {
        assertThat(calculator.cost(new Usage(10_000, 2_000), false)).isEqualByComparingTo("0");
        assertThat(calculator.cost(new Usage(10_000, 2_000, 5_000, 5_000), false)).isEqualByComparingTo("0");
    }
}
