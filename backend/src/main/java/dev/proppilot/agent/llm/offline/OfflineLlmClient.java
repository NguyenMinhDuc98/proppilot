package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.DecisionNotice;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Zero-cost stand-in for the model so the whole app (agent loop, tools, streaming, UI) works with no API key.
 * Turn 1 routes the question to a tool by keyword; turn 2 turns the tool result into a templated answer. A question
 * that needs two tools (leases that end soon, and what their leases say) has a second tool call before the answer.
 * A decision notice from the server gets a short templated confirmation and no tool.
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

        LlmResponse response;
        var decision = DecisionNotice.parse(question, json);
        if (decision.isPresent()) {
            // The notice is in English whatever the user speaks, so the language is that of what they wrote before it.
            response = textResponse(OfflineAnswerWriter.decision(decision.get(), userWroteArabicBefore(messages)), onTextDelta);
        } else if (last.content().stream().anyMatch(ContentBlock.ToolResult.class::isInstance)) {
            response = afterTools(messages, question, arabic, onTextDelta);
        } else {
            response = routeQuestion(question, arabic, onTextDelta);
        }
        return withUsage(response, request);
    }

    private LlmResponse routeQuestion(String question, boolean arabic, Consumer<String> onTextDelta) {
        var call = router.route(question);
        if (call.isEmpty()) {
            return textResponse(OfflineAnswerWriter.help(arabic), onTextDelta);
        }
        return toolUse(call.get());
    }

    private static LlmResponse toolUse(OfflineIntentRouter.Call call) {
        var use = new ContentBlock.ToolUse("offline-" + UUID.randomUUID(), call.tool(), call.args());
        return new LlmResponse(List.of(use), StopReason.TOOL_USE, Usage.ZERO);
    }

    /** What a tool answered to a call of this question. */
    private record Outcome(String tool, ContentBlock.ToolResult result) {
    }

    /** Either the next tool the question needs, or the answer from everything the tools returned. */
    private LlmResponse afterTools(List<Message> messages, String question, boolean arabic,
                                   Consumer<String> onTextDelta) {
        var outcomes = outcomesSinceQuestion(messages);
        var next = followUp(question, outcomes);
        if (next.isPresent()) {
            return toolUse(next.get());
        }
        return textResponse(answerFrom(outcomes, arabic), onTextDelta);
    }

    private Optional<OfflineIntentRouter.Call> followUp(String question, List<Outcome> outcomes) {
        var last = outcomes.get(outcomes.size() - 1);
        if (last.result().error()) {
            return Optional.empty();
        }
        try {
            return router.followUp(question, last.tool(), json.readTree(last.result().content()));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private String answerFrom(List<Outcome> outcomes, boolean arabic) {
        var parts = new ArrayList<String>();
        JsonNode listed = null;
        JsonNode searched = null;
        for (var outcome : outcomes) {
            var result = outcome.result();
            if (result.error()) {
                parts.add(OfflineAnswerWriter.error(result.content(), arabic));
                continue;
            }
            try {
                var data = json.readTree(result.content());
                parts.add(OfflineAnswerWriter.write(outcome.tool(), data, arabic));
                listed = "find_expiring_leases".equals(outcome.tool()) ? data : listed;
                searched = "search_lease_documents".equals(outcome.tool()) ? data : searched;
            } catch (JsonProcessingException e) {
                parts.add(OfflineAnswerWriter.error("unreadable tool result", arabic));
            }
        }
        if (listed != null && searched != null) {
            var note = OfflineAnswerWriter.searchedFewer(listed, searched, arabic);
            if (!note.isEmpty()) {
                parts.add(note);
            }
        }
        return String.join("\n\n", parts);
    }

    /** The tool calls of this question that have been answered, oldest first. */
    private static List<Outcome> outcomesSinceQuestion(List<Message> messages) {
        var outcomes = new ArrayList<Outcome>();
        for (int i = messages.size() - 1; i >= 1 && hasToolResults(messages.get(i)); i -= 2) {
            var uses = messages.get(i - 1).content();
            var turn = new ArrayList<Outcome>();
            for (var block : messages.get(i).content()) {
                if (block instanceof ContentBlock.ToolResult result) {
                    var tool = uses.stream()
                            .filter(ContentBlock.ToolUse.class::isInstance).map(ContentBlock.ToolUse.class::cast)
                            .filter(use -> use.id().equals(result.toolUseId()))
                            .map(ContentBlock.ToolUse::name).findFirst().orElse("unknown");
                    turn.add(new Outcome(tool, result));
                }
            }
            outcomes.addAll(0, turn);
        }
        return outcomes;
    }

    private static boolean hasToolResults(Message message) {
        return message.role() == Message.Role.USER
                && message.content().stream().anyMatch(ContentBlock.ToolResult.class::isInstance);
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

    /** Whether the last thing the user wrote before the final message was Arabic. */
    private static boolean userWroteArabicBefore(List<Message> messages) {
        for (int i = messages.size() - 2; i >= 0; i--) {
            var message = messages.get(i);
            if (message.role() != Message.Role.USER) {
                continue;
            }
            for (var block : message.content()) {
                if (block instanceof ContentBlock.Text text) {
                    return QuestionLanguage.isArabic(text.text());
                }
            }
        }
        return false;
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

    /** The examples it offers to a question it does not understand: they hold figures and a unit code of its own. */
    @Override
    public List<String> ownText() {
        return List.of(OfflineAnswerWriter.help(false), OfflineAnswerWriter.help(true));
    }
}
