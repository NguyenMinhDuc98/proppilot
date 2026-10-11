package dev.proppilot.rag.pdf;

import dev.proppilot.rag.LeaseFacts;
import dev.proppilot.rag.LeaseTerms;

/** Writes the lease document of one language. */
interface LeaseTemplate {

    /** The title of the document, which the database keeps beside the PDF. */
    String title(LeaseFacts facts);

    LeaseText write(LeaseFacts facts, LeaseTerms terms);
}
