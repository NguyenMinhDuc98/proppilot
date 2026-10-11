package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NumericNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /**
     * A string that is plain text: at most {@code maxLength} characters, no control characters other than white
     * space, and no half of a surrogate pair, which nothing downstream could encode.
     */
    public Optional<String> string(String name, int maxLength) {
        var value = string(name);
        value.ifPresent(text -> {
            if (text.length() > maxLength) {
                throw new ToolInputException(name + " must be at most " + maxLength + " characters");
            }
            if (!isPlainText(text)) {
                throw new ToolInputException(name + " must be plain text");
            }
        });
        return value;
    }

    public String requireString(String name) {
        return string(name).orElseThrow(() -> new ToolInputException(name + " is required"));
    }

    public String requireString(String name, int maxLength) {
        return string(name, maxLength).orElseThrow(() -> new ToolInputException(name + " is required"));
    }

    /**
     * An array of strings of at most {@code maxItems} entries, empty when it is missing or null. Blank entries are
     * left out, as a blank string is for {@link #string(String)}.
     */
    public List<String> strings(String name, int maxItems) {
        var node = input.get(name);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ToolInputException(name + " must be an array of strings");
        }
        if (node.size() > maxItems) {
            throw new ToolInputException(name + " must have at most " + maxItems + " items");
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual()) {
                throw new ToolInputException(name + " must be an array of strings");
            }
            var value = item.asText().strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
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

    private static boolean isPlainText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
