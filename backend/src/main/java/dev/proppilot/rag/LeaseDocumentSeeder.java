package dev.proppilot.rag;

import dev.proppilot.config.RagProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the lease documents the database is missing: one for each of the first {@code documentCount} active leases,
 * by lease id. It runs in a background thread once the application is ready, so the HTTP port never waits for it, and
 * it can run again at any time: a lease whose document is already stored by this version of the generator is left
 * alone, so a restart changes nothing.
 *
 * <p>A document that cannot be written is logged and skipped; the seeder never throws. Only one run goes at a time.
 */
@Component
public class LeaseDocumentSeeder {

    /**
     * The document at this position in the selection, counting from 1, carries the planted instruction of
     * {@link dev.proppilot.rag.pdf.PlantedInstruction}: the 29th active lease by lease id, and no other.
     */
    public static final int PLANTED_INSTRUCTION_POSITION = 29;

    private static final Logger log = LoggerFactory.getLogger(LeaseDocumentSeeder.class);

    /**
     * @param written documents written
     * @param unchanged leases that already had a current document
     * @param failed documents that could not be written
     */
    public record Result(int written, int unchanged, int failed) {
        static final Result NONE = new Result(0, 0, 0);
    }

    private final RagProperties properties;
    private final LeaseFactsRepository leases;
    private final LeaseDocumentIngestor ingestor;
    private final LeaseDocumentStore store;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    public LeaseDocumentSeeder(RagProperties properties, LeaseFactsRepository leases, LeaseDocumentIngestor ingestor,
                               LeaseDocumentStore store) {
        this.properties = properties;
        this.leases = leases;
        this.ingestor = ingestor;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedWhenReady() {
        if (properties.seedOnStartup()) {
            seedInBackground();
        }
    }

    public void seedInBackground() {
        worker = Thread.ofVirtual().name("lease-document-seeder").start(this::seed);
    }

    /**
     * Writes the missing documents now, on the calling thread. A call that comes while another run is going does
     * nothing and returns at once.
     */
    public Result seed() {
        if (!running.compareAndSet(false, true)) {
            log.info("Lease documents are already being written");
            return Result.NONE;
        }
        try {
            return seedAll();
        } catch (RuntimeException e) {
            log.error("Lease documents could not be written", e);
            return Result.NONE;
        } finally {
            running.set(false);
        }
    }

    public CorpusCounts counts() {
        return store.counts();
    }

    @PreDestroy
    void stop() {
        var thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private Result seedAll() {
        var selected = leases.firstActive(properties.documentCount());
        int written = 0;
        int unchanged = 0;
        int failed = 0;
        for (int index = 0; index < selected.size() && !Thread.currentThread().isInterrupted(); index++) {
            var facts = selected.get(index);
            try {
                if (ingestor.ingest(facts, termsOf(facts.leaseId(), index + 1))) {
                    written++;
                } else {
                    unchanged++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Lease document {} could not be written", facts.documentCode(), e);
            }
            // The work is CPU-bound and virtual threads are not preempted: let requests have the carrier in between.
            Thread.yield();
        }
        if (written > 0 || failed > 0) {
            log.info("Lease documents: {} written, {} already current, {} failed", written, unchanged, failed);
        }
        return new Result(written, unchanged, failed);
    }

    private static LeaseTerms termsOf(long leaseId, int position) {
        var terms = LeaseTerms.of(leaseId);
        return position == PLANTED_INSTRUCTION_POSITION ? terms.withPlantedInstruction() : terms;
    }
}
