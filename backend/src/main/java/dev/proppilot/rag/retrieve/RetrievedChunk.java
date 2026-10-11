package dev.proppilot.rag.retrieve;

import dev.proppilot.rag.DocumentLanguage;
import java.util.List;

/**
 * A chunk of a lease document that answers a question, with where it came from.
 *
 * @param ref the citation token without brackets, such as {@code LSE-A-504#7}
 * @param sectionNo the clause number, null for the preamble
 * @param sectionTitle the clause title, null for the preamble
 * @param text what the document says, headed by its clause heading
 * @param score the reciprocal rank fusion score; only useful for comparing chunks of one search
 * @param matchedBy the lists the chunk was found in
 */
public record RetrievedChunk(String ref, String documentCode, String documentTitle, String unitCode,
                             String tenantNameEn, String tenantNameAr, DocumentLanguage language, Integer sectionNo,
                             String sectionTitle, int page, String text, double score,
                             List<CandidateList> matchedBy) {
}
