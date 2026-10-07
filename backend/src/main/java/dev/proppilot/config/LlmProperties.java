package dev.proppilot.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM settings. Provider "offline" uses the built-in rule-based assistant (no key, no cost);
 * provider "anthropic" calls the Claude Messages API.
 */
@ConfigurationProperties(prefix = "proppilot.llm")
public record LlmProperties(
        String provider,
        String apiKey,
        String model,
        String apiUrl,
        int maxTokens,
        int timeoutSeconds,
        BigDecimal priceInputPerMtok,
        BigDecimal priceOutputPerMtok) {
}
