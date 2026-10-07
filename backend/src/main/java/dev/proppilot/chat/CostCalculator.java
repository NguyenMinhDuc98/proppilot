package dev.proppilot.chat;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.config.LlmProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Estimated USD cost of a run from token usage and the configured per-million-token prices. */
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
        var input = props.priceInputPerMtok().multiply(BigDecimal.valueOf(usage.inputTokens()));
        var output = props.priceOutputPerMtok().multiply(BigDecimal.valueOf(usage.outputTokens()));
        return input.add(output).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
