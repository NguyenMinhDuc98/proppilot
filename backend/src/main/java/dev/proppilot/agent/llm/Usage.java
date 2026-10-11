package dev.proppilot.agent.llm;

/**
 * What a model call (or a whole run) used, as the API reports it. The four counts are disjoint, so a total input is
 * their sum and each is priced on its own.
 *
 * @param inputTokens input tokens that were neither read from nor written to the prompt cache
 * @param cacheReadTokens input tokens served from the prompt cache
 * @param cacheWriteTokens input tokens written to the prompt cache
 */
public record Usage(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    public Usage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens);
    }
}
