package dev.proppilot.rag.retrieve;

/** The three ways chunks are looked up; each gives a ranked list, and the lists are fused into one. */
public enum CandidateList {
    /** Nearest by cosine distance between embeddings: matches meaning. */
    VECTOR,
    /** PostgreSQL full text search over the Arabic and the English configuration: matches words and their stems. */
    FULL_TEXT,
    /** Trigram word similarity: matches misspelled words and codes such as A-504. */
    TRIGRAM
}
