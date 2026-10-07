package dev.proppilot.agent.llm;

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
}
