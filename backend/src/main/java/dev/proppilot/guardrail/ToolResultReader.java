package dev.proppilot.guardrail;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.function.IntConsumer;

/**
 * Reads what a tool result states. A result is JSON, and a comma in it separates two values, where in a sentence it may
 * be the thousands separator of a figure: so the result is read value by value, and only the strings, the numbers
 * and the names of fields are scanned for figures and codes. The length of every array is a figure too: "12 units" is
 * grounded by a result with twelve of them, whether or not the number 12 is written in it. What follows from the
 * rows of an array, from a fraction and from a number with decimals is read for {@link DerivedFigures}.
 *
 * <p>A number is read as its plain digits, so one written with an exponent ({@code 4.8E+3}, as Jackson's tree wrote
 * a whole amount before tool results were written as plain decimals) is 4800 all the same.
 *
 * <p>The text is read as a stream, not as a tree, so a document nested a million levels deep costs no stack; Jackson
 * stops at its own depth limit. A text that is not JSON (the message of a tool error) is scanned as a sentence.
 */
final class ToolResultReader {

    private static final JsonFactory JSON = new JsonFactory();
    /** A number written longer than this, or with a larger exponent, is no figure of the data: it is read as text. */
    private static final int MAX_NUMBER_LENGTH = 40;
    private static final int MAX_EXPONENT = 30;

    private ToolResultReader() {
    }

    static void read(String content, ClaimScanner.Claims claims, IntConsumer arrayLengths, DerivedFigures derived) {
        try (var parser = JSON.createParser(content)) {
            var open = new OpenContainers(arrayLengths, derived);
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                switch (token) {
                    case START_ARRAY -> open.startArray();
                    case START_OBJECT -> open.startObject();
                    case END_ARRAY -> open.endArray();
                    case END_OBJECT -> open.endObject();
                    case FIELD_NAME -> ClaimScanner.ofEvidence(parser.currentName(), claims);
                    case VALUE_STRING -> {
                        var text = parser.getText();
                        open.scalar(parser.currentName(), text);
                        ClaimScanner.ofEvidence(text, claims);
                    }
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT ->
                            number(parser.getText(), parser.currentName(), open, derived, claims);
                    case VALUE_TRUE -> open.scalar(parser.currentName(), Boolean.TRUE);
                    case VALUE_FALSE -> open.scalar(parser.currentName(), Boolean.FALSE);
                    default -> open.scalar(parser.currentName(), RowTable.NONE);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Not JSON, or nested deeper than the parser reads: what it has not read is read as a sentence.
            ClaimScanner.ofEvidence(content, claims);
        }
    }

    private static void number(String text, String field, OpenContainers open, DerivedFigures derived,
                               ClaimScanner.Claims claims) {
        var value = figureOf(text);
        if (value == null) {
            open.scalar(field, text);
            ClaimScanner.ofEvidence(text, claims);
            return;
        }
        derived.ofRate(value);
        derived.ofRounded(value);
        open.scalar(field, value.stripTrailingZeros());
        ClaimScanner.ofEvidence(hasExponent(text) ? value.toPlainString() : text, claims);
    }

    /** The value of a number, or null when it is no figure of the data: too long, or with too large an exponent. */
    private static BigDecimal figureOf(String number) {
        if (number.length() > MAX_NUMBER_LENGTH) {
            return null;
        }
        try {
            var value = new BigDecimal(number);
            return Math.abs(value.scale()) > MAX_EXPONENT ? null : value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static boolean hasExponent(String number) {
        return number.indexOf('E') >= 0 || number.indexOf('e') >= 0;
    }
}
