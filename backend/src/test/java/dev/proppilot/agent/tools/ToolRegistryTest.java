package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

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
}
