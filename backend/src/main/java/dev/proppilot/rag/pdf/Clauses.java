package dev.proppilot.rag.pdf;

import java.util.ArrayList;
import java.util.List;

/** Collects the clauses of a document and numbers them in the order they are added. */
final class Clauses {

    private final List<LeaseText.Clause> clauses = new ArrayList<>();

    Clauses add(String title, String... paragraphs) {
        return add(title, List.of(paragraphs));
    }

    Clauses add(String title, List<String> paragraphs) {
        clauses.add(new LeaseText.Clause(clauses.size() + 1, title, paragraphs));
        return this;
    }

    List<LeaseText.Clause> build() {
        return List.copyOf(clauses);
    }
}
