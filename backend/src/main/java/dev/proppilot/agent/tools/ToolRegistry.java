package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.proppilot.agent.llm.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Collects every {@link Tool} bean, exposes them to the LLM and executes calls safely. */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final TransactionTemplate readOnlyTx;

    public ToolRegistry(List<Tool> beans, PlatformTransactionManager txManager) {
        beans.stream()
                .sorted(java.util.Comparator.comparing(Tool::name))
                .forEach(tool -> {
                    if (tools.putIfAbsent(tool.name(), tool) != null) {
                        throw new IllegalStateException("Duplicate tool name: " + tool.name());
                    }
                });
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    /** Never throws: bad input and unexpected failures come back as an error result the model can react to. */
    public ToolResult execute(String name, JsonNode input) {
        var tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("Unknown tool '" + name + "'. Available tools: " + String.join(", ", tools.keySet()));
        }
        try {
            return readOnlyTx.execute(status -> tool.execute(input));
        } catch (ToolInputException e) {
            return ToolResult.error("Invalid input: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tool {} failed", name, e);
            return ToolResult.error("Tool failed internally. Try again or answer without it.");
        }
    }
}
