package dev.proppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The lease corpus: which leases get a document, and whether the documents are written when the application starts.
 *
 * @param documentCount how many leases get a document: the first this many active leases by lease id; 0 writes none
 * @param seedOnStartup write the missing documents, then embed the chunks that need it, in the background once the
 *                      application is ready
 */
@ConfigurationProperties(prefix = "proppilot.rag")
public record RagProperties(int documentCount, boolean seedOnStartup) {

    public RagProperties {
        if (documentCount < 0) {
            throw new IllegalArgumentException("RAG_DOCUMENT_COUNT must be 0 or more");
        }
    }
}
