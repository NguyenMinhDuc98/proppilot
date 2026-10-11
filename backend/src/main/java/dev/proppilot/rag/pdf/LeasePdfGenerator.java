package dev.proppilot.rag.pdf;

import dev.proppilot.rag.LeaseFacts;
import dev.proppilot.rag.LeaseTerms;
import org.springframework.stereotype.Component;

/**
 * Writes the lease document of one lease as a PDF: a pure function of the lease's facts and terms, with nothing in it
 * that depends on the clock or on chance, so the same lease always gives the same text.
 *
 * <p>The seed data is relative to the date it was migrated on, so a PDF committed to the repository would contradict
 * the database. Documents are written from the database rows when the application starts.
 */
@Component
public class LeasePdfGenerator {

    /**
     * Stored with every document. Raise it whenever a lease would get a different document: a change to a template,
     * to {@link LeaseTerms} or to the layout. Documents of another version are written again at startup.
     */
    public static final int VERSION = 1;

    private final LeaseTemplate english = new EnglishLeaseTemplate();
    private final LeaseTemplate arabic = new ArabicLeaseTemplate();
    private final LeasePdfRenderer renderer = new LeasePdfRenderer();

    /** The PDF in the language of the tenant. */
    public byte[] generate(LeaseFacts facts, LeaseTerms terms) {
        return renderer.render(templateFor(facts).write(facts, terms), facts.leaseId());
    }

    /** The title of the lease's document. */
    public String title(LeaseFacts facts) {
        return templateFor(facts).title(facts);
    }

    private LeaseTemplate templateFor(LeaseFacts facts) {
        return facts.language().rightToLeft() ? arabic : english;
    }
}
