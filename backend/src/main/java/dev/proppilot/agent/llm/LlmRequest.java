package dev.proppilot.agent.llm;

import java.util.List;

public record LlmRequest(String system, List<Message> messages, List<ToolSpec> tools) {
}
