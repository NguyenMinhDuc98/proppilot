package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NumericNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
        // canConvertToInt alone accepts 2.7 and asInt would silently truncate it to 2
        if (!node.isNumber() || !node.canConvertToInt() || !node.canConvertToExactIntegral()) {
            throw new ToolInputException(name + " must be an integer");
        }
        int value = node.asInt();
        if (value < min || value > max) {
            throw new ToolInputException(name + " must be between " + min + " and " + max);
        }
        return Optional.of(value);
    }

    public Optional<BigDecimal> decimal(String name) {
        return signedDecimal(name).map(value -> {
            if (value.signum() < 0) {
                throw new ToolInputException(name + " must not be negative");
            }
            return value;
        });
    }

    /** For callers that give a negative number a meaning of their own, such as an amount that must be above zero. */
    public Optional<BigDecimal> signedDecimal(String name) {
        var node = input.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!(node instanceof NumericNode number)) {
            throw new ToolInputException(name + " must be a number");
        }
        // JSON such as 1e999 is read as an infinite double, which has no decimal value
        if (number.isNaN()) {
            throw new ToolInputException(name + " must be a finite number");
        }
        return Optional.of(number.decimalValue());
    }

    public Optional<LocalDate> date(String name) {
        return string(name).map(raw -> {
            try {
                return LocalDate.parse(raw);
            } catch (DateTimeParseException e) {
                throw new ToolInputException(name + " must be a date like 2026-03-15");
            }
        });
    }

    public Optional<Boolean> bool(String name) {
        var node = input.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isBoolean()) {
            throw new ToolInputException(name + " must be a boolean");
        }
        return Optional.of(node.booleanValue());
    }

    public <E extends Enum<E>> Optional<E> enumValue(String name, Class<E> type) {
        return string(name).map(raw -> Arrays.stream(type.getEnumConstants())
                .filter(e -> e.name().equalsIgnoreCase(raw))
                .findFirst()
                .orElseThrow(() -> new ToolInputException(
                        name + " must be one of " + Arrays.toString(type.getEnumConstants()))));
    }
}
