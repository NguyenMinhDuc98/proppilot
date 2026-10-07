package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/** Typed, validating access to a tool's raw JSON input. */
public final class ToolArgs {

    private final JsonNode input;

    public ToolArgs(JsonNode input) {
        this.input = input == null || input.isNull() ? JsonNodeFactory.instance.objectNode() : input;
    }

    public Optional<String> string(String name) {
        var node = input.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual()) {
            throw new ToolInputException(name + " must be a string");
        }
        return Optional.of(node.asText().strip()).filter(s -> !s.isEmpty());
    }

    public String requireString(String name) {
        return string(name).orElseThrow(() -> new ToolInputException(name + " is required"));
    }

    public Optional<Integer> integer(String name, int min, int max) {
        var node = input.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.canConvertToInt() || node.isTextual()) {
            throw new ToolInputException(name + " must be an integer");
        }
        int value = node.asInt();
        if (value < min || value > max) {
            throw new ToolInputException(name + " must be between " + min + " and " + max);
        }
        return Optional.of(value);
    }

    public Optional<BigDecimal> decimal(String name) {
        var node = input.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isNumber()) {
            throw new ToolInputException(name + " must be a number");
        }
        var value = node.decimalValue();
        if (value.signum() < 0) {
            throw new ToolInputException(name + " must not be negative");
        }
        return Optional.of(value);
    }

    public <E extends Enum<E>> Optional<E> enumValue(String name, Class<E> type) {
        return string(name).map(raw -> Arrays.stream(type.getEnumConstants())
                .filter(e -> e.name().equalsIgnoreCase(raw))
                .findFirst()
                .orElseThrow(() -> new ToolInputException(
                        name + " must be one of " + Arrays.toString(type.getEnumConstants()))));
    }
}
