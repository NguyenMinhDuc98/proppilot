package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Tiny fluent builder for the JSON Schema of a tool's input object. */
public final class ToolSchema {

    private final JsonNodeFactory f = JsonNodeFactory.instance;
    private final ObjectNode properties = f.objectNode();
    private final ArrayNode required = f.arrayNode();

    private ToolSchema() {
    }

    public static ToolSchema object() {
        return new ToolSchema();
    }

    public ToolSchema string(String name, String description) {
        return property(name, "string", description);
    }

    public ToolSchema integer(String name, String description) {
        return property(name, "integer", description);
    }

    public ToolSchema number(String name, String description) {
        return property(name, "number", description);
    }

    public ToolSchema bool(String name, String description) {
        return property(name, "boolean", description);
    }

    public ToolSchema enumeration(String name, String description, String... values) {
        var prop = f.objectNode().put("type", "string").put("description", description);
        var allowed = prop.putArray("enum");
        for (var value : values) {
            allowed.add(value);
        }
        properties.set(name, prop);
        return this;
    }

    public ToolSchema required(String... names) {
        for (var name : names) {
            required.add(name);
        }
        return this;
    }

    public JsonNode build() {
        var schema = f.objectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    private ToolSchema property(String name, String type, String description) {
        properties.set(name, f.objectNode().put("type", type).put("description", description));
        return this;
    }
}
