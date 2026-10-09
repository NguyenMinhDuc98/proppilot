package dev.proppilot.agent;

import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.config.AgentProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The agent loop, written by hand: send messages and tool definitions to the model; if it asks for tools, run them,
 * append the results and ask again; stop on a final answer, a reply cut off by the token limit, or after the iteration
 * limit.
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    static final String GAVE_UP = "I could not finish this request within my step limit. Please try a narrower question.";

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentProperties props;
    private final Clock clock;

    public AgentLoop(LlmClient llm, ToolRegistry tools, AgentProperties props, Clock clock) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.clock = clock;
    }

    public AgentResult run(String question, List<ChatTurn> history, Consumer<AgentEvent> events) {
        var messages = new ArrayList<Message>(conversationSoFar(history));
        messages.add(Message.user(question));
        var system = SystemPrompt.forDate(LocalDate.now(clock));
        var toolSpecs = tools.specs();

        var usage = Usage.ZERO;
        int toolCalls = 0;
        for (int iteration = 1; iteration <= props.maxIterations(); iteration++) {
            LlmResponse response;
            try {
                response = llm.complete(new LlmRequest(system, List.copyOf(messages), toolSpecs), text -> events.accept(new AgentEvent.Token(text)));
            } catch (LlmException e) {
                log.warn("LLM call failed on iteration {} ({}): {}", iteration, e.code(), e.getMessage());
                return AgentResult.failed(e.code(), usage, toolCalls, iteration);
            }
            usage = usage.plus(response.usage());
            if (response.stopReason() == StopReason.MAX_TOKENS) {
                // Tool calls in a reply that hit the token limit may be incomplete, so none are run.
                return new AgentResult(response.text(), usage, toolCalls, iteration, AgentResult.Status.TRUNCATED);
            }
            messages.add(new Message(Message.Role.ASSISTANT, response.content()));

            var requested = response.toolUses();
            if (requested.isEmpty()) {
                return new AgentResult(response.text(), usage, toolCalls, iteration, AgentResult.Status.OK);
            }
            messages.add(new Message(Message.Role.USER, runTools(requested, events)));
            toolCalls += requested.size();
        }
        events.accept(new AgentEvent.Token(GAVE_UP));
        return new AgentResult(GAVE_UP, usage, toolCalls, props.maxIterations(), AgentResult.Status.MAX_ITERATIONS);
    }

    private List<ContentBlock> runTools(List<ContentBlock.ToolUse> requested, Consumer<AgentEvent> events) {
        var results = new ArrayList<ContentBlock>();
        for (var call : requested) {
            events.accept(new AgentEvent.ToolCall(call.name(), call.input()));
            var result = tools.execute(call.name(), call.input());
            events.accept(new AgentEvent.ToolResultEvent(call.name(), result.summary(), result.error()));
            results.add(new ContentBlock.ToolResult(call.id(), result.content(), result.error()));
        }
        return results;
    }

    /** Last N turns of plain text, starting with a user turn so the roles alternate as the API requires. */
    private List<Message> conversationSoFar(List<ChatTurn> history) {
        int from = Math.max(0, history.size() - props.maxHistoryTurns());
        var recent = history.subList(from, history.size());
        var messages = new ArrayList<Message>();
        for (var turn : recent) {
            boolean user = turn.fromUser();
            if (messages.isEmpty() && !user) {
                continue;
            }
            boolean sameRoleAsPrevious = !messages.isEmpty()
                    && (messages.get(messages.size() - 1).role() == Message.Role.USER) == user;
            if (sameRoleAsPrevious) {
                continue;
            }
            messages.add(user ? Message.user(turn.text()) : Message.assistant(turn.text()));
        }
        if (!messages.isEmpty() && messages.get(messages.size() - 1).role() == Message.Role.USER) {
            messages.remove(messages.size() - 1);
        }
        return messages;
    }
}
