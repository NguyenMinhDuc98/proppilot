package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Zero-cost stand-in for the model so the whole app (agent loop, tools, streaming, UI) works with no API key.
 * Turn 1 routes the question to a tool by keyword; turn 2 turns the tool result into a templated answer.
 */
@Component
@ConditionalOnProperty(name = "proppilot.llm.provider", havingValue = "offline", matchIfMissing = true)
public class OfflineLlmClient implements LlmClient {

    private static final int CHARS_PER_TOKEN = 4;

    private final OfflineIntentRouter router;
    private final ObjectMapper json;

    public OfflineLlmClient(ObjectMapper json) {
        this.json = json;
        this.router = new OfflineIntentRouter(json);
    }

    @Override
    public LlmResponse complete(LlmRequest request, Consumer<String> onTextDelta) {
        var messages = request.messages();
        var last = messages.get(messages.size() - 1);
        var question = latestQuestion(messages);
        boolean arabic = QuestionLanguage.isArabic(question);

        LlmResponse response = last.content().stream().anyMatch(ContentBlock.ToolResult.class::isInstance)
                ? answerFromToolResults(messages, arabic, onTextDelta)
                : routeQuestion(question, arabic, onTextDelta);
        return withUsage(response, request);
    }

    private LlmResponse routeQuestion(String question, boolean arabic, Consumer<String> onTextDelta) {
        var call = router.route(question);
        if (call.isEmpty()) {
            return textResponse(OfflineAnswerWriter.help(arabic), onTextDelta);
        }
        var use = new ContentBlock.ToolUse("offline-" + UUID.randomUUID(), call.get().tool(), call.get().args());
        return new LlmResponse(List.of(use), StopReason.TOOL_USE, Usage.ZERO);
    }

    private LlmResponse answerFromToolResults(List<Message> messages, boolean arabic, Consumer<String> onTextDelta) {
        var results = messages.get(messages.size() - 1).content();
        var toolUses = messages.get(messages.size() - 2).content();
        var answer = new StringBuilder();
        for (var block : results) {
            if (!(block instanceof ContentBlock.ToolResult result)) {
                continue;
            }
            if (result.error()) {
                answer.append(OfflineAnswerWriter.error(result.content(), arabic));
                continue;
            }
            var tool = toolUses.stream()
                    .filter(ContentBlock.ToolUse.class::isInstance).map(ContentBlock.ToolUse.class::cast)
                    .filter(use -> use.id().equals(result.toolUseId()))
                    .map(ContentBlock.ToolUse::name).findFirst().orElse("unknown");
            try {
                answer.append(OfflineAnswerWriter.write(tool, json.readTree(result.content()), arabic));
            } catch (JsonProcessingException e) {
                answer.append(OfflineAnswerWriter.error("unreadable tool result", arabic));
            }
        }
        return textResponse(answer.toString(), onTextDelta);
    }

    private static LlmResponse textResponse(String text, Consumer<String> onTextDelta) {
        for (var word : text.split("(?<= )")) {
            onTextDelta.accept(word);
        }
        return new LlmResponse(List.of(new ContentBlock.Text(text)), StopReason.END_TURN, Usage.ZERO);
    }

    /** Text of the most recent user message that is a real question (not a tool result). */
    private static String latestQuestion(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.role() != Message.Role.USER) {
                continue;
            }
            for (var block : message.content()) {
                if (block instanceof ContentBlock.Text text) {
                    return text.text();
                }
            }
        }
        return "";
    }

    /** Rough token estimate (about 4 characters each) so the usage panel is meaningful even without a real model. */
    private static LlmResponse withUsage(LlmResponse response, LlmRequest request) {
        int in = request.messages().stream().flatMap(m -> m.content().stream()).mapToInt(OfflineLlmClient::length).sum()
                / CHARS_PER_TOKEN;
        int out = response.content().stream().mapToInt(OfflineLlmClient::length).sum() / CHARS_PER_TOKEN;
        return new LlmResponse(response.content(), response.stopReason(), new Usage(in, out));
    }

    private static int length(ContentBlock block) {
        return switch (block) {
            case ContentBlock.Text t -> t.text().length();
            case ContentBlock.ToolUse u -> u.input().toString().length();
            case ContentBlock.ToolResult r -> r.content().length();
        };
    }

    @Override
    public String provider() {
        return "offline";
    }

    @Override
    public String model() {
        return "rule-based";
    }

    @Override
    public boolean billable() {
        return false;
    }
}
