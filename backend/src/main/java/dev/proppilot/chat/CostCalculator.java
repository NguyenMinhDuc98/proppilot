package dev.proppilot.chat;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.LlmProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Estimated USD cost of a run from token usage and the configured per-million-token prices. Tokens written to and read
 * from the prompt cache are billed apart from the uncached input, at their own multiples of the input price.
 */
@Component
public class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final LlmProperties props;

    public CostCalculator(LlmProperties props) {
        this.props = props;
    }

    public BigDecimal cost(Usage usage, boolean billable) {
        if (!billable) {
            return BigDecimal.ZERO.setScale(6);
        }
        var inputPrice = props.priceInputPerMtok();
        var input = inputPrice.multiply(tokens(usage.inputTokens()));
        var cacheWrites = inputPrice.multiply(props.cacheWriteMultiplier()).multiply(tokens(usage.cacheWriteTokens()));
        var cacheReads = inputPrice.multiply(props.cacheReadMultiplier()).multiply(tokens(usage.cacheReadTokens()));
        var output = props.priceOutputPerMtok().multiply(tokens(usage.outputTokens()));
        return input.add(cacheWrites).add(cacheReads).add(output).divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal tokens(int count) {
        return BigDecimal.valueOf(count);
    }
}
