package dev.proppilot.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM settings. Provider "offline" uses the built-in rule-based assistant (no key, no cost);
 * provider "anthropic" calls the Claude Messages API.
 *
 * <p>A host may pass a variable on empty, which binds as no value. The prompt caching switch, the cache multipliers, the
 * larger model's prices and the complexity threshold therefore have their default here as well as in application.yml,
 * and are boxed where a primitive could not hold "no value". A blank one then means its default: it neither stops the
 * backend nor leaves a price null for a question to fail on later.
 *
 * @param priceInputPerMtok USD per million input tokens that were neither read from nor written to the prompt cache
 * @param cacheWriteMultiplier the price of a token written to the prompt cache, as a multiple of the input price
 * @param cacheReadMultiplier the price of a token read from the prompt cache, as a multiple of the input price
 * @param promptCaching whether requests mark the prompt prefix for the API to cache
 * @param largeModel the larger model that takes the questions routed to it and the calls that fall back to it; blank
 *                   when there is none, which switches both off
 * @param largePriceInputPerMtok the input price of the larger model, USD per million tokens
 * @param largePriceOutputPerMtok the output price of the larger model, USD per million tokens
 * @param complexityThreshold the score at which a question counts as multi-step or complex and goes to the larger model
 */
@ConfigurationProperties(prefix = "proppilot.llm")
public record LlmProperties(
        String provider,
        String apiKey,
        String model,
        String apiUrl,
        int maxTokens,
        int timeoutSeconds,
        int maxRetries,
        int retryBaseDelayMs,
        BigDecimal priceInputPerMtok,
        BigDecimal priceOutputPerMtok,
        @DefaultValue("1.25") BigDecimal cacheWriteMultiplier,
        @DefaultValue("0.10") BigDecimal cacheReadMultiplier,
        @DefaultValue("true") Boolean promptCaching,
        String largeModel,
        @DefaultValue("3.00") BigDecimal largePriceInputPerMtok,
        @DefaultValue("15.00") BigDecimal largePriceOutputPerMtok,
        @DefaultValue("3") Integer complexityThreshold) {

    public LlmProperties {
        largeModel = largeModel == null ? "" : largeModel.strip();
    }

    /** Whether there is a second model to send questions to: one is named, and it is not the default model. */
    public boolean hasLargeModel() {
        return !largeModel.isEmpty() && !largeModel.equals(model);
    }
}
