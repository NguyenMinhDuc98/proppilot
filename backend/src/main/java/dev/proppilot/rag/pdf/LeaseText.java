package dev.proppilot.rag.pdf;

import dev.proppilot.rag.DocumentLanguage;
import java.util.List;

/**
 * What a lease document says, before it is laid out: a title block, the parties and the terms in a preamble, numbered
 * clauses and a signature block. A template writes it in one language and the renderer puts it on pages.
 *
 * @param title the title line, which names the unit
 * @param reference the contract number line
 * @param clauseHeadingFormat how a clause heading reads, taking the number and the title ("%d. %s")
 * @param signaturesHeading the heading of the signature block; what follows it is not part of any clause
 * @param pageFooterFormat how the footer reads, taking the page number and the page count ("Page %d of %d")
 */
record LeaseText(DocumentLanguage language, String title, String reference, List<Section> preamble,
                 List<Clause> clauses, String clauseHeadingFormat, String signaturesHeading,
                 List<String> signatureLines, String pageFooterFormat) {

    /** A bold heading and the lines under it. */
    record Section(String heading, List<String> lines) {
    }

    record Clause(int number, String title, List<String> paragraphs) {
    }

    String heading(Clause clause) {
        return RootFormat.format(clauseHeadingFormat, clause.number(), clause.title());
    }
}
