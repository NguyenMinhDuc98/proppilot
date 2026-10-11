package dev.proppilot.rag;

import dev.proppilot.config.RagProperties;
import dev.proppilot.rag.embed.EmbeddingBackfill;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the lease corpus whole: writes the documents that are missing or outdated, then gives their chunks an
 * embedding. It runs in a background thread once the application is ready, so the HTTP port never waits for it, and on
 * demand for an admin. Both steps are idempotent, so a run that finds nothing to do changes nothing and calls nobody.
 *
 * <p>Only one run goes at a time. A failure never leaves the background thread: the corpus is searched by keywords
 * with or without it.
 */
@Component
public class CorpusIndexer {

    private static final Logger log = LoggerFactory.getLogger(CorpusIndexer.class);

    private final RagProperties properties;
    private final LeaseDocumentSeeder seeder;
    private final EmbeddingBackfill backfill;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    public CorpusIndexer(RagProperties properties, LeaseDocumentSeeder seeder, EmbeddingBackfill backfill) {
        this.properties = properties;
        this.seeder = seeder;
        this.backfill = backfill;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexWhenReady() {
        if (properties.seedOnStartup()) {
            indexInBackground();
        }
    }

    private void indexInBackground() {
        worker = Thread.ofVirtual().name("lease-corpus-indexer").start(() -> {
            try {
                reindex();
            } catch (RuntimeException e) {
                log.error("The lease corpus could not be indexed", e);
            }
        });
    }

    /**
     * Seeds, then embeds, on the calling thread.
     *
     * @return what the run left behind, or empty when another run is going and this one did nothing
     */
    public Optional<ReindexResult> reindex() {
        if (!running.compareAndSet(false, true)) {
            log.info("The lease corpus is already being indexed");
            return Optional.empty();
        }
        try {
            seeder.seed();
            var embedding = backfill.run();
            var counts = seeder.counts();
            return Optional.of(new ReindexResult(counts.documents(), counts.chunks(), embedding.embedded(),
                    embedding.failed()));
        } finally {
            running.set(false);
        }
    }

    @PreDestroy
    void stop() {
        var thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
