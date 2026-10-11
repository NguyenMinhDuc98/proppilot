package dev.proppilot.rag.retrieve;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How the lease documents were searched: by keywords and meaning, or by keywords alone. */
public enum RetrievalMode {
    HYBRID,
    KEYWORD;

    /** What the embedding setting allows: {@code hybrid} when embeddings are enabled, otherwise {@code keyword}. */
    public static RetrievalMode of(boolean embeddingsEnabled) {
        return embeddingsEnabled ? HYBRID : KEYWORD;
    }

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
