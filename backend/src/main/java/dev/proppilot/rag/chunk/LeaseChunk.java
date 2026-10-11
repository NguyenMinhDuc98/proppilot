package dev.proppilot.rag.chunk;

/**
 * A piece of a lease document that is stored, searched and cited.
 *
 * @param ref the citation token without brackets: {@code LSE-A-504#7}, or {@code LSE-A-504#7a} for a part of a clause
 *            that was split, {@code LSE-A-504#0} for the preamble
 * @param ordinal the position among the chunks of the document, from 0
 * @param sectionNo the clause number, null for the preamble
 * @param sectionTitle the clause title, null for the preamble
 * @param page the page the clause heading is on (the first page for the preamble)
 * @param content what the document says, headed by the clause heading so a part reads alone
 * @param searchText the content folded for matching, see {@link dev.proppilot.text.Folding}
 * @param tokenEstimate see {@link TokenEstimate}
 */
public record LeaseChunk(String ref, int ordinal, Integer sectionNo, String sectionTitle, int page, String content,
                         String searchText, int tokenEstimate) {
}
