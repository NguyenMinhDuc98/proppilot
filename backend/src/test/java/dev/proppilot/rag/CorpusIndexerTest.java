package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.proppilot.config.RagProperties;
import dev.proppilot.rag.embed.EmbeddingBackfill;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The order and the thread of the steps, and that only one run goes at a time. */
class CorpusIndexerTest {

    private final LeaseDocumentSeeder seeder = mock(LeaseDocumentSeeder.class);
    private final EmbeddingBackfill backfill = mock(EmbeddingBackfill.class);

    private CorpusIndexer indexer(boolean seedOnStartup) {
        when(seeder.counts()).thenReturn(new CorpusCounts(30, 410));
        when(backfill.run()).thenReturn(new EmbeddingBackfill.Result(0, 0));
        return new CorpusIndexer(new RagProperties(30, seedOnStartup), seeder, backfill);
    }

    private static boolean awaited(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void startsInTheBackgroundOnlyWhenTheApplicationIsConfiguredTo() {
        var asked = new CountDownLatch(1);
        when(seeder.seed()).thenAnswer(call -> {
            asked.countDown();
            return new LeaseDocumentSeeder.Result(0, 30, 0);
        });

        indexer(false).indexWhenReady();
        // A thread that was started by mistake asks within moments, and waiting is the only way to see it not ask.
        assertThat(awaited(asked, 500)).as("a disabled indexer does not start").isFalse();

        indexer(true).indexWhenReady();
        assertThat(awaited(asked, 30_000)).as("an enabled indexer starts").isTrue();
    }

    @Test
    void embedsAfterTheDocumentsAreWrittenOnTheSameBackgroundThread() {
        var steps = Collections.synchronizedList(new ArrayList<String>());
        var done = new CountDownLatch(1);
        when(seeder.seed()).thenAnswer(call -> {
            steps.add("seed on " + Thread.currentThread().getName());
            return new LeaseDocumentSeeder.Result(30, 0, 0);
        });
        var indexer = indexer(true);
        when(backfill.run()).thenAnswer(call -> {
            steps.add("embed on " + Thread.currentThread().getName());
            done.countDown();
            return new EmbeddingBackfill.Result(410, 0);
        });

        indexer.indexWhenReady();

        assertThat(awaited(done, 30_000)).isTrue();
        assertThat(steps).containsExactly("seed on lease-corpus-indexer", "embed on lease-corpus-indexer");
    }

    @Test
    void reportsWhatTheRunLeftBehind() {
        var indexer = indexer(false);
        when(backfill.run()).thenReturn(new EmbeddingBackfill.Result(380, 20));

        var result = indexer.reindex();

        assertThat(result).contains(new ReindexResult(30, 410, 380, 20));
    }

    @Test
    void aRunThatFindsNothingToDoReportsZeroEmbedded() {
        var result = indexer(false).reindex();

        assertThat(result).contains(new ReindexResult(30, 410, 0, 0));
    }

    @Test
    void onlyOneRunGoesAtATime() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(seeder.seed()).thenAnswer(call -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new LeaseDocumentSeeder.Result(0, 30, 0);
        });
        var indexer = indexer(false);
        var running = Thread.ofVirtual().start(indexer::reindex);

        try {
            assertThat(awaited(started, 30_000)).isTrue();

            assertThat(indexer.reindex()).isEmpty();
        } finally {
            release.countDown();
            running.join(30_000);
        }
        verify(seeder, times(1)).seed();
        verify(backfill, times(1)).run();
    }

    @Test
    void aRunThatFailsLetsTheNextOneGo() {
        var indexer = indexer(false);
        when(backfill.run()).thenThrow(new IllegalStateException("database is gone"))
                .thenReturn(new EmbeddingBackfill.Result(5, 0));

        assertThatThrownBy(indexer::reindex).isInstanceOf(IllegalStateException.class);

        assertThat(indexer.reindex()).contains(new ReindexResult(30, 410, 5, 0));
    }

    @Test
    void aFailureInTheBackgroundRunDoesNotLeaveTheThread() {
        var failed = new CountDownLatch(1);
        var indexer = indexer(true);
        when(backfill.run()).thenAnswer(call -> {
            failed.countDown();
            throw new IllegalStateException("database is gone");
        });
        var uncaught = new ArrayList<Throwable>();
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
        try {
            indexer.indexWhenReady();
            assertThat(awaited(failed, 30_000)).isTrue();
            // The thread finishes right after the failure; its end is what an uncaught exception would be seen at.
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        assertThat(uncaught).isEmpty();
        verify(seeder, times(1)).seed();
    }
}
