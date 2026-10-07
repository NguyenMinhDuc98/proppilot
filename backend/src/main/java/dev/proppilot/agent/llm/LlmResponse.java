package dev.proppilot.agent.llm;

import java.util.List;

public record LlmResponse(List<ContentBlock> content, StopReason stopReason, Usage usage) {

    public List<ContentBlock.ToolUse> toolUses() {
        return content.stream()
                .filter(ContentBlock.ToolUse.class::isInstance)
                .map(ContentBlock.ToolUse.class::cast)
                .toList();
    }

    public String text() {
        var sb = new StringBuilder();
        for (var block : content) {
            if (block instanceof ContentBlock.Text text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
