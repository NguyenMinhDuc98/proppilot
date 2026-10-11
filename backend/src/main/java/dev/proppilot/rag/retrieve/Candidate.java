package dev.proppilot.rag.retrieve;

/** A chunk one of the lists found. The document code and the ordinal are what ties between chunks are broken by. */
record Candidate(long id, String documentCode, int ordinal) {
}
