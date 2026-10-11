package dev.proppilot.chat;

import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.LlmProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Estimated USD cost of a run from token usage and the configured per-million-token prices. A call is priced by the
 * model it was made with, so a run that used two models costs what each of them charged. Tokens written to and read
 * from the prompt cache are billed apart from the uncached input, at their own multiples of that model's input price.
 */
@Component
public class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int SCALE = 6;

    private final LlmProperties props;

    public CostCalculator(LlmProperties props) {
        this.props = props;
    }

    /** One call. A model that is not the larger one is priced at the default model's prices. */
    public BigDecimal cost(String model, Usage usage) {
        return perMillion(model, usage).divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    /** A run: the sum of its calls, rounded once. Nothing when the provider costs nothing per token. */
    public BigDecimal cost(List<LlmCallRecord> calls, boolean billable) {
        if (!billable) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        var total = BigDecimal.ZERO;
        for (var call : calls) {
            total = total.add(perMillion(call.model(), call.usage()));
        }
        return total.divide(MILLION, SCALE, RoundingMode.HALF_UP);
    }

    /** The price of the call in USD per million, that is, before it is divided by the million tokens. */
    private BigDecimal perMillion(String model, Usage usage) {
        boolean large = props.hasLargeModel() && props.largeModel().equals(model);
        var inputPrice = large ? props.largePriceInputPerMtok() : props.priceInputPerMtok();
        var outputPrice = large ? props.largePriceOutputPerMtok() : props.priceOutputPerMtok();
        var input = inputPrice.multiply(tokens(usage.inputTokens()));
        var cacheWrites = inputPrice.multiply(props.cacheWriteMultiplier()).multiply(tokens(usage.cacheWriteTokens()));
        var cacheReads = inputPrice.multiply(props.cacheReadMultiplier()).multiply(tokens(usage.cacheReadTokens()));
        var output = outputPrice.multiply(tokens(usage.outputTokens()));
        return input.add(cacheWrites).add(cacheReads).add(output);
    }

    private static BigDecimal tokens(int count) {
        return BigDecimal.valueOf(count);
    }
}
