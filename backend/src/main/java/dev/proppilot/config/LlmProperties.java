package dev.proppilot.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM settings. Provider "offline" uses the built-in rule-based assistant (no key, no cost);
 * provider "anthropic" calls the Claude Messages API.
 *
 * @param priceInputPerMtok USD per million input tokens that were neither read from nor written to the prompt cache
 * @param cacheWriteMultiplier the price of a token written to the prompt cache, as a multiple of the input price
 * @param cacheReadMultiplier the price of a token read from the prompt cache, as a multiple of the input price
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
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier) {
}
