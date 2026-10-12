package dev.proppilot.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

/**
 * Writes what a tool states as JSON, for the model and for the web app, with every amount as the plain decimal it is.
 *
 * <p>{@link ObjectMapper#valueToTree} reads its result back as a tree, and the tree strips the trailing zeros of a
 * {@link java.math.BigDecimal}: {@code 4800.00} becomes {@code 48} with a scale of -2, which writes as {@code 4.8E+3},
 * and {@code 80} as {@code 8E+1}. A model reads that less surely than 4800, and a person never wrote it. So a value is
 * written as text first, with its amounts exact and plain ({@code 4800.00} stays {@code 4800.00}, {@code 1E+3} is
 * {@code 1000}), and a tree is read from that text with the zeros kept. No amount passes through a float.
 */
final class ToolJson {

    private ToolJson() {
    }

    /** The JSON text of {@code data}. */
    static String write(ObjectMapper json, Object data) {
        try {
            return json.writer().with(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Tool data cannot be written as JSON", e);
        }
    }

    /** The tree of {@code data}, whose amounts write as they were given. */
    static JsonNode tree(ObjectMapper json, Object data) {
        try {
            return json.readerFor(JsonNode.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                    .readTree(write(json, data));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Tool data cannot be read as a JSON tree", e);
        }
    }
}
