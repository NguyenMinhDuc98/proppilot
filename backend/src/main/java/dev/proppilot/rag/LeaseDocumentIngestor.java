package dev.proppilot.rag;

import dev.proppilot.rag.chunk.LeaseChunker;
import dev.proppilot.rag.pdf.LeasePdfGenerator;
import dev.proppilot.rag.pdf.PdfTextExtractor;
import org.springframework.stereotype.Service;

/**
 * Puts a lease document through the pipeline a real PDF would go through: generate the PDF, read the text back out of
 * it, cut the text into chunks and store all of it. The chunks come from the extracted text and not from the template,
 * so what is searched is what a reader of the PDF would find.
 */
@Service
public class LeaseDocumentIngestor {

    private final LeasePdfGenerator generator;
    private final PdfTextExtractor extractor;
    private final LeaseChunker chunker;
    private final LeaseDocumentStore store;

    public LeaseDocumentIngestor(LeasePdfGenerator generator, PdfTextExtractor extractor, LeaseChunker chunker,
                                 LeaseDocumentStore store) {
        this.generator = generator;
        this.extractor = extractor;
        this.chunker = chunker;
        this.store = store;
    }

    /**
     * Writes the document of a lease unless the lease already has one from this version of the generator. Chunks are
     * stored without embeddings: keyword search needs none.
     *
     * @return whether a document was written
     */
    public boolean ingest(LeaseFacts facts, LeaseTerms terms) {
        if (store.hasDocument(facts.leaseId(), LeasePdfGenerator.VERSION)) {
            return false;
        }
        var pdf = generator.generate(facts, terms);
        var pages = extractor.pages(pdf);
        var chunks = chunker.chunk(facts.documentCode(), pages);
        return store.replace(new NewDocument(facts.leaseId(), facts.documentCode(), generator.title(facts),
                facts.language(), pages.size(), LeasePdfGenerator.VERSION, pdf, chunks));
    }
}
