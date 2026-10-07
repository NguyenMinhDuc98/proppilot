package dev.proppilot.agent.llm;

import java.util.List;

public record Message(Role role, List<ContentBlock> content) {

    public enum Role { USER, ASSISTANT }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(new ContentBlock.Text(text)));
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(new ContentBlock.Text(text)));
    }
}
