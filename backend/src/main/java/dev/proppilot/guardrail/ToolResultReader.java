package dev.proppilot.guardrail;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.function.IntConsumer;

/**
 * Reads what a tool result states. A result is JSON, and a comma in it separates two values, where in a sentence it may
 * be the thousands separator of a figure: so the result is read value by value, and only the strings, the numbers
 * and the names of fields are scanned for figures and codes. The length of every array is a figure too: "12 units" is
 * grounded by a result with twelve of them, whether or not the number 12 is written in it.
 *
 * <p>A number is read as its plain digits: Jackson writes a whole amount of a tool as {@code 4.8E+3}, which is 4800.
 *
 * <p>The text is read as a stream, not as a tree, so a document nested a million levels deep costs no stack; Jackson
 * stops at its own depth limit. A text that is not JSON (the message of a tool error) is scanned as a sentence.
 */
final class ToolResultReader {

    private static final JsonFactory JSON = new JsonFactory();
    /** Stands for an object on the stack of open containers: its fields are not elements of an array. */
    private static final int[] OBJECT = new int[0];
    /** A number written longer than this, or with a larger exponent, is no figure of the data: it is read as text. */
    private static final int MAX_NUMBER_LENGTH = 40;
    private static final int MAX_EXPONENT = 30;

    private ToolResultReader() {
    }

    static void read(String content, ClaimScanner.Claims claims, IntConsumer arrayLengths) {
        try (var parser = JSON.createParser(content)) {
            var open = new ArrayDeque<int[]>();
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                switch (token) {
                    case START_ARRAY -> {
                        countElement(open);
                        open.push(new int[1]);
                    }
                    case START_OBJECT -> {
                        countElement(open);
                        open.push(OBJECT);
                    }
                    case END_ARRAY -> arrayLengths.accept(open.pop()[0]);
                    case END_OBJECT -> open.pop();
                    case FIELD_NAME -> ClaimScanner.ofEvidence(parser.currentName(), claims);
                    case VALUE_STRING -> {
                        countElement(open);
                        ClaimScanner.ofEvidence(parser.getText(), claims);
                    }
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        countElement(open);
                        ClaimScanner.ofEvidence(plain(parser.getText()), claims);
                    }
                    default -> countElement(open);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Not JSON, or nested deeper than the parser reads: what it has not read is read as a sentence.
            ClaimScanner.ofEvidence(content, claims);
        }
    }

    /** {@code 4.8E+3} as {@code 4800}; any other number as it is. */
    private static String plain(String number) {
        boolean exponent = number.indexOf('E') >= 0 || number.indexOf('e') >= 0;
        if (!exponent || number.length() > MAX_NUMBER_LENGTH) {
            return number;
        }
        try {
            var value = new BigDecimal(number);
            return Math.abs(value.scale()) > MAX_EXPONENT ? number : value.toPlainString();
        } catch (NumberFormatException notANumber) {
            return number;
        }
    }

    private static void countElement(ArrayDeque<int[]> open) {
        if (!open.isEmpty() && open.peek() != OBJECT) {
            open.peek()[0]++;
        }
    }
}
