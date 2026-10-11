package dev.proppilot.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.TestLlmProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";

    private final CostCalculator calculator = calculator("1.00", "5.00", "1.25", "0.10");
    /** The default prices are 1.00 and 5.00; the larger model's are 3.00 and 15.00. */
    private final CostCalculator routing = new CostCalculator(TestLlmProperties.defaults().model(SMALL)
            .largeModel(LARGE).build());

    private static CostCalculator calculator(String input, String output, String cacheWrite, String cacheRead) {
        return new CostCalculator(TestLlmProperties.defaults().model(SMALL).prices(input, output)
                .cacheMultipliers(cacheWrite, cacheRead).build());
    }

    private static LlmCallRecord call(String model, Usage usage) {
        return new LlmCallRecord(1, model, usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                usage.cacheWriteTokens(), 100, StopReason.END_TURN, LlmCallRecord.Outcome.OK, null, Instant.EPOCH);
    }

    @Test
    void pricesInputAndOutputTokensPerMillion() {
        var cost = calculator.cost(SMALL, new Usage(10_000, 2_000));

        assertThat(cost).isEqualByComparingTo("0.020000");
    }

    @Test
    void pricesCacheWritesAndReadsAtTheirMultiplesOfTheInputPrice() {
        // 10,000 x 1.00 + 20,000 x 1.25 + 100,000 x 0.10 + 2,000 x 5.00 = 55,000 per million
        var cost = calculator.cost(SMALL, new Usage(10_000, 2_000, 100_000, 20_000));

        assertThat(cost).isEqualByComparingTo("0.055000");
    }

    @Test
    void aCallThatUsedTheCacheCostsMoreThanTheSameCallWithoutIt() {
        var withoutCache = calculator.cost(SMALL, new Usage(30, 12));
        var withCache = calculator.cost(SMALL, new Usage(30, 12, 4_000, 250));

        assertThat(withCache).isGreaterThan(withoutCache);
        assertThat(withCache).isEqualByComparingTo("0.000803");
    }

    @Test
    void theCacheMultiplesFollowTheInputPrice() {
        var larger = calculator("5.00", "25.00", "1.25", "0.10");

        var cost = larger.cost(SMALL, new Usage(0, 0, 1_000_000, 1_000_000));

        assertThat(cost).isEqualByComparingTo("6.750000");
    }

    @Test
    void theLargerModelIsPricedAtItsOwnPrices() {
        // 10,000 x 3.00 + 20,000 x 3.75 + 100,000 x 0.30 + 2,000 x 15.00 = 165,000 per million
        var usage = new Usage(10_000, 2_000, 100_000, 20_000);

        assertThat(routing.cost(LARGE, usage)).isEqualByComparingTo("0.165000");
        assertThat(routing.cost(SMALL, usage)).isEqualByComparingTo("0.055000");
    }

    @Test
    void theLargerModelsPricesAreTheConfiguredOnes() {
        var opus = new CostCalculator(TestLlmProperties.defaults().model(SMALL).largeModel("claude-opus-4-5")
                .largePrices("5.00", "25.00").build());

        var cost = opus.cost("claude-opus-4-5", new Usage(10_000, 2_000));

        assertThat(cost).isEqualByComparingTo("0.100000");
    }

    @Test
    void aModelThatIsNeitherConfiguredOneIsPricedAtTheDefaultPrices() {
        var cost = routing.cost("some-other-model", new Usage(10_000, 2_000, 100_000, 20_000));

        assertThat(cost).isEqualByComparingTo("0.055000");
    }

    @Test
    void theLargePricesAreNotUsedWhenNoLargerModelIsConfigured() {
        var cost = calculator.cost(LARGE, new Usage(10_000, 2_000));

        assertThat(cost).isEqualByComparingTo("0.020000");
    }

    @Test
    void aRunThatUsedTwoModelsAndTheCacheCostsTheSumOfItsCalls() {
        // Haiku: 10,000 x 1.00 + 20,000 x 1.25 + 100,000 x 0.10 + 2,000 x 5.00 = 55,000 per million
        // Sonnet: 4,000 x 3.00 + 8,000 x 3.75 + 50,000 x 0.30 + 1,000 x 15.00 = 72,000 per million
        var failed = new LlmCallRecord(1, SMALL, 0, 0, 0, 0, 5_200, null, LlmCallRecord.Outcome.ERROR,
                ErrorCode.LLM_OVERLOADED, Instant.EPOCH);
        var calls = List.of(failed, call(SMALL, new Usage(10_000, 2_000, 100_000, 20_000)),
                call(LARGE, new Usage(4_000, 1_000, 50_000, 8_000)));

        var cost = routing.cost(calls, true);

        assertThat(cost).isEqualByComparingTo("0.127000");
    }

    @Test
    void aRunIsRoundedOnceAndNotCallByCall() {
        // Each call costs 0.0000004 on its own, which rounds to nothing; the three together cost 0.0000012
        var calls = List.of(call(SMALL, new Usage(0, 0, 4, 0)), call(SMALL, new Usage(0, 0, 4, 0)),
                call(SMALL, new Usage(0, 0, 4, 0)));

        assertThat(calculator.cost(calls, true)).isEqualByComparingTo("0.000001");
        assertThat(calculator.cost(SMALL, new Usage(0, 0, 4, 0))).isEqualByComparingTo("0");
    }

    @Test
    void nonBillableProvidersCostNothing() {
        var calls = List.of(call("rule-based", new Usage(10_000, 2_000, 5_000, 5_000)));

        assertThat(calculator.cost(calls, false)).isEqualByComparingTo("0");
        assertThat(calculator.cost(List.of(), false)).isEqualByComparingTo("0");
    }

    @Test
    void aRunWithNoCallsCostsNothing() {
        assertThat(calculator.cost(List.of(), true)).isEqualByComparingTo("0");
    }
}
