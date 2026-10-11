package dev.proppilot.rag.embed;

/** A vector in the text form pgvector reads, bound as a parameter and cast with {@code ?::vector}. */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    public static String of(float[] vector) {
        var literal = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
