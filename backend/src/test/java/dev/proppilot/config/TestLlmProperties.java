package dev.proppilot.config;

import java.math.BigDecimal;

/** {@link LlmProperties} for a test: the defaults of application.yml, with only what the test cares about changed. */
public final class TestLlmProperties {

    private String provider = "anthropic";
    private String model = "claude-haiku-4-5-20251001";
    private String apiUrl = "http://localhost/v1/messages";
    private int timeoutSeconds = 60;
    private int maxRetries = 3;
    private int retryBaseDelayMs = 1000;
    private BigDecimal priceInputPerMtok = new BigDecimal("1.00");
    private BigDecimal priceOutputPerMtok = new BigDecimal("5.00");
    private BigDecimal cacheWriteMultiplier = new BigDecimal("1.25");
    private BigDecimal cacheReadMultiplier = new BigDecimal("0.10");
    private boolean promptCaching = true;
    private String largeModel = "";
    private BigDecimal largePriceInputPerMtok = new BigDecimal("3.00");
    private BigDecimal largePriceOutputPerMtok = new BigDecimal("15.00");
    private int complexityThreshold = 3;

    private TestLlmProperties() {
    }

    public static TestLlmProperties defaults() {
        return new TestLlmProperties();
    }

    public TestLlmProperties provider(String provider) {
        this.provider = provider;
        return this;
    }

    public TestLlmProperties model(String model) {
        this.model = model;
        return this;
    }

    public TestLlmProperties apiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        return this;
    }

    public TestLlmProperties timeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }

    public TestLlmProperties retries(int maxRetries, int baseDelayMs) {
        this.maxRetries = maxRetries;
        this.retryBaseDelayMs = baseDelayMs;
        return this;
    }

    public TestLlmProperties prices(String input, String output) {
        this.priceInputPerMtok = new BigDecimal(input);
        this.priceOutputPerMtok = new BigDecimal(output);
        return this;
    }

    public TestLlmProperties cacheMultipliers(String write, String read) {
        this.cacheWriteMultiplier = new BigDecimal(write);
        this.cacheReadMultiplier = new BigDecimal(read);
        return this;
    }

    public TestLlmProperties promptCaching(boolean promptCaching) {
        this.promptCaching = promptCaching;
        return this;
    }

    public TestLlmProperties largeModel(String largeModel) {
        this.largeModel = largeModel;
        return this;
    }

    public TestLlmProperties largePrices(String input, String output) {
        this.largePriceInputPerMtok = new BigDecimal(input);
        this.largePriceOutputPerMtok = new BigDecimal(output);
        return this;
    }

    public TestLlmProperties complexityThreshold(int complexityThreshold) {
        this.complexityThreshold = complexityThreshold;
        return this;
    }

    public LlmProperties build() {
        return new LlmProperties(provider, "test-api-key-123", model, apiUrl, 1024, timeoutSeconds, maxRetries,
                retryBaseDelayMs, priceInputPerMtok, priceOutputPerMtok, cacheWriteMultiplier, cacheReadMultiplier,
                promptCaching, largeModel, largePriceInputPerMtok, largePriceOutputPerMtok,
                complexityThreshold);
    }
}
