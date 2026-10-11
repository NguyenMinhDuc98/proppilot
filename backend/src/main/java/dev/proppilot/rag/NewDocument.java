package dev.proppilot.rag;

import dev.proppilot.rag.chunk.LeaseChunk;
import java.util.List;

/** A lease document ready to be stored: its PDF and the chunks cut from the text of that PDF. */
record NewDocument(long leaseId, String code, String title, DocumentLanguage language, int pageCount,
                   int generatorVersion, byte[] pdf, List<LeaseChunk> chunks) {
}
