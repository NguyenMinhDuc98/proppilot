package dev.proppilot.agent.llm;

import java.util.List;
import java.util.function.Consumer;

/** One model turn. Implementations stream answer text through {@code onTextDelta} as it arrives. */
public interface LlmClient {

    LlmResponse complete(LlmRequest request, Consumer<String> onTextDelta);

    String provider();

    String model();

    /** False for providers that cost nothing per token. */
    default boolean billable() {
        return true;
    }

    /**
     * Text that this client writes into its answers by itself and not from the data, such as the examples of what to
     * ask. It is the server's own words, so the grounding guardrail takes the figures and codes in it as given.
     */
    default List<String> ownText() {
        return List.of();
    }
}
