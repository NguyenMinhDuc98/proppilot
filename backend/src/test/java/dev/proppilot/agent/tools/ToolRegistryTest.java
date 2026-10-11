package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class ToolRegistryTest {

    private static Tool tool(String name, Function<JsonNode, ToolResult> body) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "d"; }
            public JsonNode inputSchema() { return ToolSchema.object().build(); }
            public ToolResult execute(JsonNode input) { return body.apply(input); }
        };
    }

    private static ToolRegistry registry(Tool... tools) {
        return new ToolRegistry(List.of(tools), mock(PlatformTransactionManager.class));
    }

    private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

    @Test
    void exposesSpecsSortedByName() {
        var registry = registry(tool("b", i -> ToolResult.error("x")), tool("a", i -> ToolResult.error("x")));

        assertThat(registry.specs()).extracting("name").containsExactly("a", "b");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> registry(tool("a", i -> null), tool("a", i -> null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void convertsInputExceptionIntoErrorResult() {
        var registry = registry(tool("a", i -> { throw new ToolInputException("bad city"); }));

        var result = registry.execute("a", EMPTY);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("Invalid input: bad city");
    }

    @Test
    void hidesUnexpectedFailureDetails() {
        var registry = registry(tool("a", i -> { throw new IllegalStateException("jdbc password=secret"); }));

        var result = registry.execute("a", EMPTY);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).doesNotContain("secret");
    }

    @Test
    void aProposalToolRunsOutsideTheReadOnlyTransactionAndGetsTheRunContext() {
        var transactions = mock(PlatformTransactionManager.class);
        var seen = new ArrayList<ToolContext>();
        var proposing = new ProposalTool() {
            public String name() { return "propose"; }
            public String description() { return "d"; }
            public JsonNode inputSchema() { return ToolSchema.object().build(); }
            public ToolResult propose(JsonNode input, ToolContext context) {
                seen.add(context);
                return ToolResult.error("stored");
            }
        };
        var registry = new ToolRegistry(List.of(proposing), transactions);
        var context = new ToolContext(UUID.randomUUID());

        registry.execute("propose", EMPTY, context);
        registry.execute("propose", EMPTY);

        assertThat(seen).containsExactly(context, ToolContext.NONE);
        verifyNoInteractions(transactions);
    }

    @Test
    void everyOtherToolRunsInAReadOnlyTransaction() {
        var transactions = mock(PlatformTransactionManager.class);
        var registry = new ToolRegistry(List.of(tool("a", i -> ToolResult.error("x"))), transactions);

        registry.execute("a", EMPTY);

        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
    }

    @Test
    void aToolThatManagesItsOwnTransactionsDoesNotHoldTheRegistrysWhileItWaits() {
        var transactions = mock(PlatformTransactionManager.class);
        var calls = new ArrayList<JsonNode>();
        var waiting = new OwnTransactionTool() {
            public String name() { return "waiting"; }
            public String description() { return "d"; }
            public JsonNode inputSchema() { return ToolSchema.object().build(); }
            public ToolResult execute(JsonNode input) {
                calls.add(input);
                return ToolResult.error("done");
            }
        };
        var registry = new ToolRegistry(List.of(waiting), transactions);

        var result = registry.execute("waiting", EMPTY);

        assertThat(calls).containsExactly(EMPTY);
        assertThat(result.content()).isEqualTo("done");
        verifyNoInteractions(transactions);
    }

    @Test
    void aToolThatManagesItsOwnTransactionsStillGetsItsInputErrorsAsResults() {
        var failing = new OwnTransactionTool() {
            public String name() { return "failing"; }
            public String description() { return "d"; }
            public JsonNode inputSchema() { return ToolSchema.object().build(); }
            public ToolResult execute(JsonNode input) { throw new ToolInputException("query is required"); }
        };

        var result = registry(failing).execute("failing", EMPTY);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("Invalid input: query is required");
    }
}
