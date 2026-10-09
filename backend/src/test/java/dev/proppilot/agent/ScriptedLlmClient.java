package dev.proppilot.agent;

import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/** Test double for the LLM: plays back queued responses and records every request it receives. */
public class ScriptedLlmClient implements LlmClient {

    private final Queue<Object> script = new ArrayDeque<>();
    public final List<LlmRequest> requests = new ArrayList<>();

    public ScriptedLlmClient thenText(String text) {
        script.add(new LlmResponse(List.of(new ContentBlock.Text(text)), StopReason.END_TURN, new Usage(100, 20)));
        return this;
    }

    public ScriptedLlmClient thenToolCall(String id, String tool, String jsonArgs) {
        try {
            var input = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonArgs);
            script.add(new LlmResponse(List.of(new ContentBlock.ToolUse(id, tool, input)), StopReason.TOOL_USE, new Usage(50, 10)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    public ScriptedLlmClient thenFail(ErrorCode code, String message) {
        script.add(new LlmException(message, code));
        return this;
    }

    /** For tests that share one instance as a Spring bean. */
    public void reset() {
        script.clear();
        requests.clear();
    }

    @Override
    public LlmResponse complete(LlmRequest request, Consumer<String> onTextDelta) {
        requests.add(request);
        var next = script.remove();
        if (next instanceof LlmException e) {
            throw e;
        }
        var response = (LlmResponse) next;
        if (!response.text().isEmpty()) {
            onTextDelta.accept(response.text());
        }
        return response;
    }

    @Override
    public String provider() {
        return "scripted";
    }

    @Override
    public String model() {
        return "test";
    }
}
