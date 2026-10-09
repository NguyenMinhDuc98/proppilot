package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.ContentBlock;
import dev.proppilot.agent.llm.LlmResponse;
import dev.proppilot.agent.llm.Message;
import dev.proppilot.agent.llm.StopReason;
import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.tools.Tool;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.agent.tools.ToolSchema;
import dev.proppilot.config.AgentProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class AgentLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);

    private final List<String> executedWith = new ArrayList<>();

    private final Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "Echoes its input"; }
        public JsonNode inputSchema() { return ToolSchema.object().string("text", "text").required("text").build(); }
        public ToolResult execute(JsonNode input) {
            if (!input.hasNonNull("text")) {
                throw new ToolInputException("text is required");
            }
            executedWith.add(input.get("text").asText());
            return ToolResult.ok(JSON, java.util.Map.of("echo", input.get("text").asText()), "echoed");
        }
    };

    private AgentLoop loop(ScriptedLlmClient llm, int maxIterations) {
        var registry = new ToolRegistry(List.of(echo), mock(PlatformTransactionManager.class));
        return new AgentLoop(llm, registry, new AgentProperties(maxIterations, 10), CLOCK);
    }

    @Test
    void answersDirectlyWhenModelRequestsNoTools() {
        var llm = new ScriptedLlmClient().thenText("Hello");
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run("hi", List.of(), events::add);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.answer()).isEqualTo("Hello");
        assertThat(result.toolCalls()).isZero();
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(events).containsExactly(new AgentEvent.Token("Hello"));
    }

    @Test
    void executesRequestedToolAndFeedsResultBackToModel() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{\"text\":\"ping\"}")
                .thenText("Done: ping");
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run("say ping", List.of(), events::add);

        assertThat(executedWith).containsExactly("ping");
        assertThat(result.answer()).isEqualTo("Done: ping");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.usage().inputTokens()).isEqualTo(150);
        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResultEvent", "Token");

        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.toolUseId()).isEqualTo("t1");
        assertThat(toolResult.content()).contains("ping");
        assertThat(toolResult.error()).isFalse();
    }

    @Test
    void invalidToolInputIsReturnedToTheModelAsAnError() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "echo", "{}")
                .thenText("Sorry, I need text");

        var result = loop(llm, 6).run("go", List.of(), e -> { });

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.error()).isTrue();
        assertThat(toolResult.content()).contains("text is required");
    }

    @Test
    void unknownToolIsAnErrorResultNotAnException() {
        var llm = new ScriptedLlmClient()
                .thenToolCall("t1", "does_not_exist", "{}")
                .thenText("ok");

        var result = loop(llm, 6).run("go", List.of(), e -> { });

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        var secondRequest = llm.requests.get(1).messages();
        var toolResult = (ContentBlock.ToolResult) secondRequest.get(secondRequest.size() - 1).content().get(0);
        assertThat(toolResult.error()).isTrue();
        assertThat(toolResult.content()).contains("Unknown tool").contains("echo");
    }

    @Test
    void stopsAfterMaxIterations() {
        var llm = new ScriptedLlmClient();
        for (int i = 0; i < 3; i++) {
            llm.thenToolCall("t" + i, "echo", "{\"text\":\"again\"}");
        }
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 3).run("loop forever", List.of(), events::add);

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_ITERATIONS);
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(3);
        assertThat(llm.requests).hasSize(3);
        assertThat(events.get(events.size() - 1)).isEqualTo(new AgentEvent.Token(AgentLoop.GAVE_UP));
    }

    @Test
    void llmFailureEndsTheRunWithAnErrorCodeAndNoProviderText() {
        var llm = new ScriptedLlmClient().thenFail(ErrorCode.LLM_OVERLOADED, "Claude API returned 529: upstream-secret");

        var result = loop(llm, 6).run("hi", List.of(), e -> { });

        assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LLM_OVERLOADED);
        assertThat(result.answer()).isEqualTo(ErrorCode.LLM_OVERLOADED.message()).doesNotContain("upstream-secret");
    }

    @Test
    void aReplyCutOffByTheTokenLimitEndsTheRunAsTruncatedAndRunsNoTools() throws Exception {
        var toolCall = new ContentBlock.ToolUse("t1", "echo", JSON.readTree("{\"text\":\"ping\"}"));
        var llm = new ScriptedLlmClient().then(new LlmResponse(
                List.of(new ContentBlock.Text("The answer is cut"), toolCall), StopReason.MAX_TOKENS, new Usage(30, 2048)));
        var events = new ArrayList<AgentEvent>();

        var result = loop(llm, 6).run("go", List.of(), events::add);

        assertThat(result.status()).isEqualTo(AgentResult.Status.TRUNCATED);
        assertThat(result.answer()).isEqualTo("The answer is cut");
        assertThat(result.usage()).isEqualTo(new Usage(30, 2048));
        assertThat(result.toolCalls()).isZero();
        assertThat(executedWith).isEmpty();
        assertThat(llm.requests).hasSize(1);
        assertThat(events).noneMatch(AgentEvent.ToolCall.class::isInstance);
    }

    @Test
    void sendsSystemPromptWithTodaysDateAndToolSpecs() {
        var llm = new ScriptedLlmClient().thenText("ok");

        loop(llm, 6).run("hi", List.of(), e -> { });

        var request = llm.requests.get(0);
        assertThat(request.system()).contains("2026-03-15").contains("Never invent");
        assertThat(request.tools()).extracting(t -> t.name()).containsExactly("echo");
    }

    @Test
    void historyIsTrimmedAndKeepsRolesAlternating() {
        var llm = new ScriptedLlmClient().thenText("ok");
        var history = List.of(
                new ChatTurn("assistant", "stale greeting"),
                new ChatTurn("user", "first question"),
                new ChatTurn("assistant", "first answer"),
                new ChatTurn("user", "dangling question without answer"));

        loop(llm, 6).run("second question", history, e -> { });

        var messages = llm.requests.get(0).messages();
        assertThat(messages).extracting(Message::role).containsExactly(
                Message.Role.USER, Message.Role.ASSISTANT, Message.Role.USER);
        assertThat(((ContentBlock.Text) messages.get(2).content().get(0)).text()).isEqualTo("second question");
    }
}
