package dev.proppilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.LlmRequest;
import dev.proppilot.agent.routing.ComplexityAssessor;
import dev.proppilot.agent.routing.ComplexityRule;
import dev.proppilot.agent.routing.ModelRouter;
import dev.proppilot.agent.routing.RoutedReason;
import dev.proppilot.agent.tools.Tool;
import dev.proppilot.agent.tools.ToolRegistry;
import dev.proppilot.agent.tools.ToolResult;
import dev.proppilot.agent.tools.ToolSchema;
import dev.proppilot.config.AgentProperties;
import dev.proppilot.config.TestLlmProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Which model the loop sends a run to. */
class AgentLoopRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(90);
    private static final BooleanSupplier NEVER = () -> false;
    private static final String SMALL = "claude-haiku-4-5-20251001";
    private static final String LARGE = "claude-sonnet-4-5";
    private static final String SIMPLE = "Who is more than 30 days late on rent?";
    private static final String COMPLEX = "Which leases end in the next 60 days and require 90 days notice?";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-15T10:00:00Z"));
    private final ScriptedLlmClient llm = new ScriptedLlmClient().servingAs("anthropic", SMALL);
    private final List<AgentEvent> events = new ArrayList<>();

    private final Tool echo = new Tool() {
        public String name() { return "echo"; }
        public String description() { return "Echoes its input"; }
        public JsonNode inputSchema() { return ToolSchema.object().string("text", "text").build(); }
        public ToolResult execute(JsonNode input) {
            return ToolResult.ok(JSON, java.util.Map.of("echo", input.path("text").asText()), "echoed");
        }
    };

    private AgentLoop loop(int maxIterations, boolean largerModelConfigured) {
        var props = TestLlmProperties.defaults().model(SMALL);
        if (largerModelConfigured) {
            props.largeModel(LARGE);
        }
        var router = new ModelRouter(llm, props.build(), new ComplexityAssessor());
        var registry = new ToolRegistry(List.of(echo), mock(PlatformTransactionManager.class));
        var agentProps = new AgentProperties(maxIterations, 10, (int) RUN_TIMEOUT.toSeconds());
        return new AgentLoop(llm, registry, agentProps, clock, router);
    }

    private AgentResult ask(String question) {
        return ask(question, 6, true, List.of());
    }

    private AgentResult ask(String question, int maxIterations, boolean largerModelConfigured, List<ChatTurn> history) {
        var request = AgentRequest.question(question, history, UUID.randomUUID());
        return loop(maxIterations, largerModelConfigured).run(request, events::add, NEVER);
    }

    private List<String> modelsAsked() {
        return llm.requests.stream().map(LlmRequest::model).toList();
    }

    @Test
    void aComplexQuestionIsAnsweredByTheLargerModel() {
        llm.thenText("Two leases.");

        var result = ask(COMPLEX);

        assertThat(modelsAsked()).containsExactly(LARGE);
        assertThat(result.route().model()).isEqualTo(LARGE);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.COMPLEX);
        assertThat(result.route().complexity().rules())
                .containsExactly(ComplexityRule.SEVERAL_DATA_AREAS, ComplexityRule.WINDOW_WITH_LEASE_TERM);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(LARGE);
    }

    @Test
    void aSimpleQuestionIsAnsweredByTheDefaultModel() {
        llm.thenText("Three tenants.");

        var result = ask(SIMPLE);

        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(result.route().model()).isEqualTo(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.route().complexity().score()).isZero();
    }

    @Test
    void everyCallOfARunGoesToTheModelChosenBeforeItStarted() {
        llm.thenToolCall("t1", "echo", "{\"text\":\"one\"}").thenToolCall("t2", "echo", "{\"text\":\"two\"}")
                .thenText("done");

        var result = ask(COMPLEX);

        assertThat(modelsAsked()).containsExactly(LARGE, LARGE, LARGE);
        assertThat(result.llmCallRecords()).extracting(LlmCallRecord::model).containsExactly(LARGE, LARGE, LARGE);
    }

    @Test
    void withoutALargerModelEveryCallNamesTheDefaultModelAndNothingIsScored() {
        llm.thenText("Two leases.");

        var result = ask(COMPLEX, 6, false, List.of());

        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.route().complexity()).isNull();
    }

    @Test
    void theConfirmationOfADecisionIsNotScoredAndNeedsNoTools() {
        llm.thenText("The payment was recorded.");
        var notice = COMPLEX + " " + COMPLEX + " compare each of them and then summarise";

        var request = AgentRequest.confirmation(notice, List.of(), UUID.randomUUID());
        var result = loop(6, true).run(request, events::add, NEVER);

        assertThat(modelsAsked()).containsExactly(SMALL);
        assertThat(result.route().reason()).isEqualTo(RoutedReason.DEFAULT);
        assertThat(result.route().complexity()).isNull();
    }

    @Test
    void theMessagesOfTheConversationSoFarAreScored() {
        var history = new ArrayList<ChatTurn>();
        for (int i = 0; i < 3; i++) {
            history.add(new ChatTurn("user", "question " + i));
            history.add(new ChatTurn("assistant", "answer " + i));
        }
        llm.thenText("ok");

        var result = ask("And the next one?", 6, true, history);

        assertThat(result.route().complexity().rules()).containsExactly(ComplexityRule.LONG_CONVERSATION);
    }
}
