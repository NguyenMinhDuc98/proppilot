package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.MutableClock;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.rag.FakeEmbeddingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The backfill on the real corpus and real pgvector, with a provider that is a test double. */
class EmbeddingBackfillIT extends FakeEmbeddingIntegrationTest {

    @Autowired
    EmbeddingBackfill backfill;

    private int chunks() {
        return count("select count(*) from lease_chunks");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void givesEveryChunkAnEmbeddingFromTheConfiguredModel() {
        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(chunks(), 0));
        assertThat(count("select count(*) from lease_chunks where embedding is null")).isZero();
        assertThat(jdbc.queryForList("select distinct embedding_model from lease_chunks", String.class))
                .containsExactly("fake-embedding-1");
    }

    @Test
    void storesTheVectorTheProviderGave() {
        backfill.run();

        var row = jdbc.queryForMap("""
                select c.id, d.title || chr(10) || c.content as text,
                       c.embedding <=> ?::vector as distance
                from lease_chunks c join lease_documents d on d.id = c.document_id
                order by c.id limit 1""", VectorLiteral.of(fake.vector(firstText())));

        assertThat(row.get("text")).isEqualTo(firstText());
        assertThat((Double) row.get("distance")).isLessThan(1e-6);
    }

    private String firstText() {
        return jdbc.queryForObject("""
                select d.title || chr(10) || c.content
                from lease_chunks c join lease_documents d on d.id = c.document_id
                order by c.id limit 1""", String.class);
    }

    @Test
    void embedsEachChunkWithItsDocumentTitleAndClauseHeadingInFront() {
        backfill.run();

        var texts = fake.documentCalls.stream().flatMap(List::stream).toList();
        var notice = jdbc.queryForMap("""
                select d.title, c.content from lease_chunks c join lease_documents d on d.id = c.document_id
                where d.language = 'en' and c.section_title = 'Notice period' order by c.id limit 1""");
        var preamble = jdbc.queryForMap("""
                select d.title, c.content from lease_chunks c join lease_documents d on d.id = c.document_id
                where d.language = 'en' and c.section_no is null order by c.id limit 1""");
        assertThat(texts).contains(notice.get("title") + "\n" + notice.get("content"));
        assertThat((String) notice.get("title")).startsWith("Residential Lease Agreement");
        assertThat((String) notice.get("content")).startsWith("4. Notice period\n");
        assertThat(texts).as("a preamble has no heading, only the title in front")
                .contains(preamble.get("title") + "\n" + preamble.get("content"));
        assertThat(texts).hasSize(chunks());
    }

    @Test
    void sendsBatchesOfAtMostThirtyTwo() {
        backfill.run();

        assertThat(fake.documentCalls).isNotEmpty().allSatisfy(call -> assertThat(call).hasSizeLessThanOrEqualTo(32));
        assertThat(fake.documentCalls).hasSize((chunks() + 31) / 32);
    }

    @Test
    void aSecondRunWithNothingToDoMakesNoCall() {
        backfill.run();
        int calls = fake.documentCalls.size();

        var second = backfill.run();

        assertThat(second).isEqualTo(new EmbeddingBackfill.Result(0, 0));
        assertThat(fake.documentCalls).hasSize(calls);
    }

    @Test
    void embedsOnlyTheChunksThatHaveNoEmbedding() {
        backfill.run();
        int inDocument = jdbc.update(
                "update lease_chunks set embedding = null, embedding_model = null where ref like 'LSE-B-102#%'");
        fake.documentCalls.clear();

        var result = backfill.run();

        assertThat(inDocument).isGreaterThan(10);
        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(inDocument, 0));
        assertThat(fake.documentCalls).hasSize(1);
        assertThat(fake.documentCalls.get(0))
                .allSatisfy(text -> assertThat(text).startsWith("Residential Lease Agreement – Unit B-102"));
    }

    @Test
    void embedsAgainWhenTheModelChanges() {
        backfill.run();
        fake.useModel("fake-embedding-2");
        int callsBefore = fake.documentCalls.size();

        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(chunks(), 0));
        assertThat(fake.documentCalls.size() - callsBefore).isEqualTo((chunks() + 31) / 32);
        assertThat(jdbc.queryForList("select distinct embedding_model from lease_chunks", String.class))
                .containsExactly("fake-embedding-2");
    }

    @Test
    void anEmbeddingOfNoKnownModelIsEmbeddedAgain() {
        backfill.run();
        int unlabelled = jdbc.update("update lease_chunks set embedding_model = null where ref like 'LSE-B-103#%'");

        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(unlabelled, 0));
    }

    @Test
    void aProviderFailureStopsTheRunKeepsWhatWasDoneAndNeverThrows() {
        fake.beforeDocumentCall(call -> {
            if (call == 3) {
                throw LlmException.retryable("Voyage API returned 429", ErrorCode.LLM_OVERLOADED);
            }
        });

        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(64, chunks() - 64));
        assertThat(count("select count(*) from lease_chunks where embedding is null")).isEqualTo(chunks() - 64);
        assertThat(fake.documentCalls).as("the run stops at the first batch that fails").hasSize(3);
    }

    @Test
    void theNextRunCarriesOnFromWhatIsStored() {
        fake.beforeDocumentCall(call -> {
            if (call == 3) {
                throw LlmException.retryable("Voyage API returned 429", ErrorCode.LLM_OVERLOADED);
            }
        });
        backfill.run();
        fake.reset();

        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(chunks() - 64, 0));
        assertThat(fake.documentCalls.stream().mapToInt(List::size).sum()).isEqualTo(chunks() - 64);
    }

    @Test
    void aRejectedKeyLeavesEveryChunkWithoutAnEmbedding() {
        fake.beforeDocumentCall(call -> {
            throw new LlmException("Voyage API returned 401", ErrorCode.LLM_AUTH);
        });

        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(0, chunks()));
        assertThat(count("select count(*) from lease_chunks where embedding is not null")).isZero();
    }

    @Test
    void stopsWhenTheTimeLimitIsUsedUp() {
        var manual = new MutableClock(Instant.parse("2026-10-11T00:00:00Z"));
        fake.beforeDocumentCall(call -> manual.advance(Duration.ofMinutes(2)));

        var result = new EmbeddingBackfill(jdbc, fake, manual).run();

        assertThat(EmbeddingBackfill.TIME_LIMIT).isEqualTo(Duration.ofMinutes(5));
        assertThat(fake.documentCalls).as("the clock passes the limit during the third call").hasSize(3);
        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(96, chunks() - 96));
    }

    @Test
    void stopsWhenTheThreadIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            var result = backfill.run();

            assertThat(result).isEqualTo(new EmbeddingBackfill.Result(0, chunks()));
            assertThat(fake.documentCalls).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNothingWhenEmbeddingsAreOff() {
        fake.enabled(false);

        var result = backfill.run();

        assertThat(result).isEqualTo(new EmbeddingBackfill.Result(0, 0));
        assertThat(fake.documentCalls).isEmpty();
        assertThat(count("select count(*) from lease_chunks where embedding is not null")).isZero();
    }
}
