package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.proppilot.config.RagProperties;
import dev.proppilot.rag.chunk.LeaseChunk;
import dev.proppilot.rag.chunk.LeaseChunker;
import dev.proppilot.rag.pdf.LeasePdfGenerator;
import dev.proppilot.rag.pdf.PdfTextExtractor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** Writing the corpus is idempotent and safe: a second run changes nothing, and a failure never gets out. */
class LeaseDocumentIngestionIT extends CorpusIntegrationTest {

    @Autowired
    LeaseFactsRepository leases;
    @Autowired
    LeaseDocumentStore store;
    @Autowired
    PdfTextExtractor extractor;
    @Autowired
    LeaseChunker chunker;

    @Test
    void aSecondRunChangesNothing() {
        var documentsBefore = rows("select id, created_at, generator_version from lease_documents order by id");
        var chunksBefore = rows("select id, document_id, ref, content from lease_chunks order by id");

        var result = seeder.seed();

        assertThat(result).isEqualTo(new LeaseDocumentSeeder.Result(0, 30, 0));
        assertThat(rows("select id, created_at, generator_version from lease_documents order by id"))
                .isEqualTo(documentsBefore);
        assertThat(rows("select id, document_id, ref, content from lease_chunks order by id")).isEqualTo(chunksBefore);
        assertThat(seeder.counts().documents()).isEqualTo(30);
    }

    @Test
    void aDocumentFromAnotherGeneratorVersionIsWrittenAgainAndTheOthersAreLeftAlone() {
        var before = rows("select lease_id, id from lease_documents order by lease_id");
        var chunksBefore = count("select count(*) from lease_chunks");
        var stale = before.subList(0, 3).stream().map(row -> row.get("lease_id")).toList();
        jdbc.update("update lease_documents set generator_version = ? where lease_id in (?, ?, ?)",
                LeasePdfGenerator.VERSION - 1, stale.get(0), stale.get(1), stale.get(2));

        var result = seeder.seed();

        var after = rows("select lease_id, id from lease_documents order by lease_id");
        assertThat(result).isEqualTo(new LeaseDocumentSeeder.Result(3, 27, 0));
        assertThat(after).hasSize(30);
        for (int i = 0; i < 30; i++) {
            var sameDocument = after.get(i).get("id").equals(before.get(i).get("id"));
            assertThat(sameDocument).as("lease %s", before.get(i).get("lease_id")).isEqualTo(i >= 3);
        }
        assertThat(count("select count(*) from lease_documents where generator_version = "
                + LeasePdfGenerator.VERSION)).isEqualTo(30);
        assertThat(count("select count(*) from lease_chunks")).isEqualTo(chunksBefore);
    }

    @Test
    void aMissingDocumentIsWrittenAgainWithItsChunks() {
        long leaseId = jdbc.queryForObject("select min(lease_id) from lease_documents", Long.class);
        int chunks = count("select count(*) from lease_chunks");
        int chunksOfDocument = jdbc.queryForObject("""
                select count(*) from lease_chunks c join lease_documents d on d.id = c.document_id
                where d.lease_id = ?""", Integer.class, leaseId);
        jdbc.update("delete from lease_documents where lease_id = ?", leaseId);
        assertThat(count("select count(*) from lease_chunks")).isEqualTo(chunks - chunksOfDocument);

        var result = seeder.seed();

        assertThat(result.written()).isEqualTo(1);
        assertThat(count("select count(*) from lease_chunks")).isEqualTo(chunks);
        assertThat(seeder.counts()).isEqualTo(new CorpusCounts(30, chunks));
    }

    @Test
    void theCorpusCountsMatchWhatIsStored() {
        var counts = seeder.counts();

        assertThat(counts.documents()).isEqualTo(count("select count(*) from lease_documents"));
        assertThat(counts.chunks()).isEqualTo(count("select count(*) from lease_chunks"));
        assertThat(counts.chunks()).isGreaterThan(counts.documents() * 12);
    }

    @Test
    void aDocumentWhoseChunksCannotBeStoredLeavesNothingBehind() {
        var facts = leases.firstActive(1).get(0);
        jdbc.update("delete from lease_documents where lease_id = ?", facts.leaseId());
        var twice = new LeaseChunk(facts.documentCode() + "#1", 0, 1, "Rent", 1, "1. Rent", "1. rent", 2);
        var document = new NewDocument(facts.leaseId(), facts.documentCode(), "Title", facts.language(), 1,
                LeasePdfGenerator.VERSION, new byte[] {'%', 'P', 'D', 'F'}, List.of(twice, twice));

        assertThatThrownBy(() -> store.replace(document)).isInstanceOf(DataAccessException.class);

        assertThat(count("select count(*) from lease_documents where lease_id = " + facts.leaseId())).isZero();
        assertThat(count("select count(*) from lease_chunks where ref = '" + facts.documentCode() + "#1'")).isZero();
        assertThat(seeder.seed().written()).isEqualTo(1);
    }

    @Test
    void twoWritersOfOneLeaseTakeTurnsAndTheDocumentIsWrittenOnce() throws Exception {
        var facts = leases.firstActive(1).get(0);
        var terms = LeaseTerms.of(facts.leaseId());
        int chunks = count("select count(*) from lease_chunks c join lease_documents d on d.id = c.document_id "
                + "where d.lease_id = " + facts.leaseId());
        jdbc.update("delete from lease_documents where lease_id = ?", facts.leaseId());
        var ingestor = new LeaseDocumentIngestor(new LeasePdfGenerator(), extractor, chunker, store);
        var together = new CyclicBarrier(2);

        var first = CompletableFuture.supplyAsync(() -> ingestAfter(together, ingestor, facts, terms));
        var second = CompletableFuture.supplyAsync(() -> ingestAfter(together, ingestor, facts, terms));

        assertThat(List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(count("select count(*) from lease_documents where lease_id = " + facts.leaseId())).isEqualTo(1);
        assertThat(count("select count(*) from lease_chunks c join lease_documents d on d.id = c.document_id "
                + "where d.lease_id = " + facts.leaseId())).isEqualTo(chunks);
    }

    @Test
    void theSeederDoesNotThrowWhenGeneratingADocumentFails() {
        var generator = mock(LeasePdfGenerator.class);
        when(generator.generate(any(), any())).thenThrow(new IllegalStateException("no glyph for U+0000"));
        // Leases 31 to 35 have no document, so the generator is called for each of them.
        var failing = seederFor(generator, 35);

        var result = failing.seed();

        assertThat(result).isEqualTo(new LeaseDocumentSeeder.Result(0, 30, 5));
        assertThat(count("select count(*) from lease_documents")).isEqualTo(30);
        assertThatCode(failing::seed).doesNotThrowAnyException();
    }

    @Test
    void aFailureOfTheWholeRunIsLoggedAndNotThrown() {
        var repository = mock(LeaseFactsRepository.class);
        when(repository.firstActive(30)).thenThrow(new IllegalStateException("database is gone"));
        var seeder = new LeaseDocumentSeeder(new RagProperties(30, false), repository,
                new LeaseDocumentIngestor(new LeasePdfGenerator(), extractor, chunker, store), store);

        assertThat(seeder.seed()).isEqualTo(new LeaseDocumentSeeder.Result(0, 0, 0));
    }

    @Test
    void onlyOneRunGoesAtATime() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var generator = mock(LeasePdfGenerator.class);
        doAnswer(call -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            throw new IllegalStateException("stop here");
        }).when(generator).generate(any(), any());
        var seeder = seederFor(generator, 31);
        var running = Thread.ofVirtual().start(seeder::seed);

        try {
            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(seeder.seed()).isEqualTo(new LeaseDocumentSeeder.Result(0, 0, 0));
        } finally {
            release.countDown();
            running.join(30_000);
        }
        assertThat(count("select count(*) from lease_documents")).isEqualTo(30);
    }

    @Test
    void theSeederStartsInTheBackgroundOnlyWhenTheApplicationIsConfiguredTo() {
        var repository = mock(LeaseFactsRepository.class);
        var asked = new CountDownLatch(1);
        when(repository.firstActive(30)).thenAnswer(call -> {
            asked.countDown();
            return List.of();
        });
        var ingestor = new LeaseDocumentIngestor(new LeasePdfGenerator(), extractor, chunker, store);

        new LeaseDocumentSeeder(new RagProperties(30, false), repository, ingestor, store).seedWhenReady();
        // A thread that was started by mistake asks within moments, and waiting is the only way to see it not ask.
        assertThat(awaited(asked, 500)).as("a disabled seeder does not start").isFalse();

        new LeaseDocumentSeeder(new RagProperties(30, true), repository, ingestor, store).seedWhenReady();
        assertThat(awaited(asked, 30_000)).as("an enabled seeder starts").isTrue();
    }

    private static boolean ingestAfter(CyclicBarrier barrier, LeaseDocumentIngestor ingestor, LeaseFacts facts,
                                       LeaseTerms terms) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ingestor.ingest(facts, terms);
    }

    private LeaseDocumentSeeder seederFor(LeasePdfGenerator generator, int count) {
        return new LeaseDocumentSeeder(new RagProperties(count, false), leases,
                new LeaseDocumentIngestor(generator, extractor, chunker, store), store);
    }

    private static boolean awaited(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.queryForList(sql);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
