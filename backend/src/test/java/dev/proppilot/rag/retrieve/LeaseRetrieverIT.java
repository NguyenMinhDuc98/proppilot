package dev.proppilot.rag.retrieve;

import static dev.proppilot.rag.retrieve.CandidateList.FULL_TEXT;
import static dev.proppilot.rag.retrieve.CandidateList.TRIGRAM;
import static dev.proppilot.rag.retrieve.CandidateList.VECTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.rag.DocumentLanguage;
import dev.proppilot.rag.FakeEmbeddingIntegrationTest;
import dev.proppilot.rag.embed.EmbeddingBackfill;
import java.util.Comparator;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/** Hybrid retrieval on the real corpus and real pgvector, with the embeddings of a deterministic fake provider. */
class LeaseRetrieverIT extends FakeEmbeddingIntegrationTest {

    @Autowired
    LeaseRetriever wired;
    @Autowired
    EmbeddingBackfill backfill;
    @Autowired
    PlatformTransactionManager transactions;

    /** Built for each test, so the cache of embedded questions of one test is not the next test's. */
    private LeaseRetriever retriever;

    /** A stored lease document, found by the language it is written in. */
    private record Document(long leaseId, String code) {
    }

    @BeforeEach
    void everyChunkIsEmbedded() {
        backfill.run();
        retriever = new LeaseRetriever(jdbc, transactions, new QueryEmbedder(fake));
    }

    @Test
    void theRetrieverOfTheApplicationUsesTheConfiguredEmbeddingClient() {
        var result = wired.retrieve("a question only this test asks", null);

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(fake.queryCalls).containsExactly("a question only this test asks");
    }

    private Document document(String language, int position) {
        var row = jdbc.queryForList("""
                select lease_id, code from lease_documents where language = ? order by lease_id limit 1 offset ?""",
                language, position).get(0);
        return new Document(((Number) row.get("lease_id")).longValue(), (String) row.get("code"));
    }

    private String refOf(Document document, String sectionTitle) {
        return jdbc.queryForObject("""
                select c.ref from lease_chunks c join lease_documents d on d.id = c.document_id
                where d.code = ? and c.section_title = ? order by c.ordinal limit 1""", String.class,
                document.code(), sectionTitle);
    }

    @Test
    void anEnglishQuestionFindsTheEnglishClauseWithEverythingACitationNeeds() {
        var lease = document("en", 0);

        var result = retriever.retrieve("late payment fee", Set.of(lease.leaseId()));

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.HYBRID);
        var top = result.chunks().get(0);
        var stored = jdbc.queryForMap("""
                select c.ref, c.content, c.page, c.section_no, d.title, t.name_en, t.name_ar
                from lease_chunks c join lease_documents d on d.id = c.document_id
                join leases l on l.id = d.lease_id join tenants t on t.id = l.tenant_id
                where c.ref = ?""", refOf(lease, "Late payment"));
        assertThat(top.ref()).isEqualTo(stored.get("ref"));
        assertThat(top.documentCode()).isEqualTo(lease.code());
        assertThat(top.documentTitle()).isEqualTo(stored.get("title"));
        assertThat(top.unitCode()).isEqualTo(lease.code().substring("LSE-".length()));
        assertThat(top.tenantNameEn()).isEqualTo(stored.get("name_en"));
        assertThat(top.tenantNameAr()).isEqualTo(stored.get("name_ar"));
        assertThat(top.language()).isEqualTo(DocumentLanguage.EN);
        assertThat(top.sectionNo()).isEqualTo(stored.get("section_no"));
        assertThat(top.sectionTitle()).isEqualTo("Late payment");
        assertThat(top.page()).isEqualTo(stored.get("page"));
        assertThat(top.text()).isEqualTo(stored.get("content")).startsWith(top.sectionNo() + ". Late payment");
        assertThat(top.matchedBy()).containsExactly(VECTOR, FULL_TEXT, TRIGRAM);
        assertThat(top.score()).isGreaterThan(result.chunks().get(1).score());
    }

    @Test
    void anArabicQuestionFindsTheArabicClause() {
        var lease = document("ar", 0);

        var result = retriever.retrieve("غرامة التأخير", Set.of(lease.leaseId()));

        var top = result.chunks().get(0);
        assertThat(top.ref()).isEqualTo(refOf(lease, "غرامة التأخير"));
        assertThat(top.language()).isEqualTo(DocumentLanguage.AR);
        assertThat(top.matchedBy()).contains(VECTOR, FULL_TEXT);
    }

    @Test
    void thePartOfALongClauseThatAnswersIsCitedWithItsLetter() {
        var annexed = jdbc.queryForMap("""
                select d.code, d.lease_id from lease_documents d join lease_chunks c on c.document_id = d.id
                where d.language = 'en' and c.ref like '%#13a' order by d.lease_id limit 1""");
        var leaseId = ((Number) annexed.get("lease_id")).longValue();

        var quiet = retriever.retrieve("quiet hours from 10 pm to 7 am", Set.of(leaseId), 1);
        var keys = retriever.retrieve("keys and access cards replacement", Set.of(leaseId), 1);

        assertThat(quiet.chunks()).extracting(RetrievedChunk::ref).containsExactly(annexed.get("code") + "#13a");
        assertThat(keys.chunks()).extracting(RetrievedChunk::ref).containsExactly(annexed.get("code") + "#13b");
        assertThat(quiet.chunks().get(0).sectionNo()).isEqualTo(13);
    }

    @Test
    void aLeaseFilterExcludesEveryOtherLease() {
        var first = document("en", 0);
        var second = document("en", 1);

        var everyone = retriever.retrieve("notice period", null, 8);
        var one = retriever.retrieve("notice period", Set.of(first.leaseId()), 8);
        var two = retriever.retrieve("notice period", Set.of(first.leaseId(), second.leaseId()), 8);

        assertThat(everyone.chunks()).extracting(RetrievedChunk::documentCode).doesNotContain(first.code() + "x")
                .hasSizeGreaterThan(1);
        assertThat(one.chunks()).hasSize(8).extracting(RetrievedChunk::documentCode).containsOnly(first.code());
        assertThat(two.chunks()).extracting(RetrievedChunk::documentCode)
                .containsOnly(first.code(), second.code()).contains(first.code(), second.code());
    }

    @Test
    void aLeaseFilterKeepsTheVectorListFromRunningOutOfThatLeasesChunks() {
        var lease = document("en", 2);

        var result = retriever.retrieve("pets", Set.of(lease.leaseId()), 8);

        assertThat(result.chunks()).hasSize(8).allSatisfy(chunk -> {
            assertThat(chunk.documentCode()).isEqualTo(lease.code());
        });
        assertThat(result.chunks()).anySatisfy(chunk -> assertThat(chunk.matchedBy()).contains(VECTOR));
    }

    @Test
    void searchingPerLeaseGivesEveryLeaseItsOwnBestChunksAndEmbedsTheQuestionOnce() {
        var leases = new java.util.LinkedHashSet<Long>();
        var codes = new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            var lease = document(i % 2 == 0 ? "en" : "ar", i / 2);
            leases.add(lease.leaseId());
            codes.add(lease.code());
        }

        var result = retriever.retrievePerLease("late fee", leases, 2);

        assertThat(result.chunks()).hasSize(10);
        assertThat(result.chunks()).extracting(RetrievedChunk::documentCode)
                .containsExactly(codes.get(0), codes.get(0), codes.get(1), codes.get(1), codes.get(2), codes.get(2),
                        codes.get(3), codes.get(3), codes.get(4), codes.get(4));
        assertThat(fake.queryCalls).containsExactly("late fee");
        assertThat(result.retrieval()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void searchingPerLeaseKeepsEachLeasesShareBetweenOneAndEight() {
        var lease = document("en", 0);

        assertThat(retriever.retrievePerLease("pets", Set.of(lease.leaseId()), 0).chunks()).hasSize(1);
        assertThat(retriever.retrievePerLease("pets", Set.of(lease.leaseId()), 100).chunks()).hasSize(8);
    }

    @Test
    void searchingPerLeaseInAnEmptySetFindsNothingAndEmbedsNothing() {
        var result = retriever.retrievePerLease("pets", Set.of(), 2);

        assertThat(result.chunks()).isEmpty();
        assertThat(fake.queryCalls).isEmpty();
    }

    @Test
    void anEmptySetOfLeasesFindsNothingAndDoesNotFallBackToAllOfThem() {
        var result = retriever.retrieve("notice period", Set.of());

        assertThat(result.chunks()).isEmpty();
        assertThat(fake.queryCalls).as("nothing is embedded for a search that cannot find anything").isEmpty();
    }

    @Test
    void aLeaseWithoutADocumentFindsNothing() {
        long leaseWithoutDocument = jdbc.queryForObject("""
                select max(id) from leases where id not in (select lease_id from lease_documents)""", Long.class);

        var result = retriever.retrieve("notice period", Set.of(leaseWithoutDocument));

        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void topKIsFourByDefaultAndKeptBetweenOneAndEight() {
        assertThat(retriever.retrieve("notice period", null).chunks()).hasSize(4);
        assertThat(retriever.retrieve("notice period", null, 1).chunks()).hasSize(1);
        assertThat(retriever.retrieve("notice period", null, 8).chunks()).hasSize(8);
        assertThat(retriever.retrieve("notice period", null, 0).chunks()).hasSize(1);
        assertThat(retriever.retrieve("notice period", null, -3).chunks()).hasSize(1);
        assertThat(retriever.retrieve("notice period", null, 100).chunks()).hasSize(8);
    }

    @Test
    void aChunkThatNoKeywordFindsIsFoundByMeaning() {
        var lease = document("en", 0);

        var result = retriever.retrieve("relinquish", Set.of(lease.leaseId()));

        var top = result.chunks().get(0);
        assertThat(result.retrieval()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(top.ref()).isEqualTo(refOf(lease, "Early termination"));
        assertThat(top.matchedBy()).containsExactly(VECTOR);
        assertThat(top.text().toLowerCase()).doesNotContain("relinquish");
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.matchedBy()).containsExactly(VECTOR));
    }

    @Test
    void theSameQuestionFindsNothingWithoutEmbeddings() {
        var lease = document("en", 0);
        fake.enabled(false);

        var result = retriever.retrieve("relinquish", Set.of(lease.leaseId()));

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void aChunkAllThreeListsLikeOutranksOneThatOnlyOneDoes() {
        var lease = document("en", 0);

        var result = retriever.retrieve("early termination fee", Set.of(lease.leaseId()), 8);

        var scores = result.chunks().stream().map(RetrievedChunk::score).toList();
        assertThat(result.chunks().get(0).ref()).isEqualTo(refOf(lease, "Early termination"));
        assertThat(result.chunks().get(0).matchedBy()).containsExactly(VECTOR, FULL_TEXT, TRIGRAM);
        assertThat(scores).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(result.chunks().subList(1, result.chunks().size()))
                .allSatisfy(chunk -> assertThat(chunk.matchedBy().size()).isLessThan(3));
    }

    @Test
    void theSameQuestionAlwaysGivesTheSameChunksInTheSameOrder() {
        var first = retriever.retrieve("rent increase on renewal", null, 8);
        var second = retriever.retrieve("rent increase on renewal", null, 8);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void aFailedEmbeddingFallsBackToKeywordsAndSaysSo() {
        var lease = document("en", 0);
        fake.failQueriesWith(LlmException.retryable("Voyage API returned 429", ErrorCode.LLM_OVERLOADED));

        var result = retriever.retrieve("late payment fee", Set.of(lease.leaseId()));

        assertThat(fake.queryCalls).hasSize(1);
        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.chunks().get(0).ref()).isEqualTo(refOf(lease, "Late payment"));
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.matchedBy()).doesNotContain(VECTOR));
    }

    @Test
    void anyOtherFailureOfTheEmbeddingIsAbsorbedToo() {
        fake.failQueriesWith(new IllegalStateException("boom"));

        var result = retriever.retrieve("late payment fee", null);

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.chunks()).isNotEmpty();
    }

    @Test
    void searchesByKeywordsWhenEmbeddingsAreOff() {
        fake.enabled(false);

        var result = retriever.retrieve("late payment fee", null);

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(fake.queryCalls).isEmpty();
    }

    @Test
    void aQuestionAskedAgainIsNotEmbeddedAgain() {
        retriever.retrieve("who pays for minor repairs?", null);
        var again = retriever.retrieve("who pays for minor repairs?", null);

        assertThat(fake.queryCalls).hasSize(1);
        assertThat(again.retrieval()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void aFailedEmbeddingIsAskedForAgainNextTime() {
        fake.failQueriesWith(new IllegalStateException("boom"));
        retriever.retrieve("who pays for minor repairs?", null);
        fake.failQueriesWith(null);

        var again = retriever.retrieve("who pays for minor repairs?", null);

        assertThat(fake.queryCalls).hasSize(2);
        assertThat(again.retrieval()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void chunksEmbeddedByAnotherModelAreNotSearchedByMeaning() {
        var lease = document("en", 0);
        fake.useModel("fake-embedding-2");

        var result = retriever.retrieve("relinquish", Set.of(lease.leaseId()));

        assertThat(result.chunks()).as("their vectors are in another space, so nothing is near").isEmpty();
        backfill.run();
        assertThat(retriever.retrieve("relinquish", Set.of(lease.leaseId())).chunks()).isNotEmpty();
    }

    @Test
    void aQuestionIsStrippedAndCutToFiveHundredCharactersBeforeAnythingSeesIt() {
        var question = "  " + "notice period ".repeat(60) + "  ";

        var result = retriever.retrieve(question, null);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(fake.queryCalls).hasSize(1);
        assertThat(fake.queryCalls.get(0)).hasSizeLessThanOrEqualTo(LeaseRetriever.MAX_QUERY_LENGTH)
                .isEqualTo(fake.queryCalls.get(0).strip());
    }

    @Test
    void aQuestionCutInTheMiddleOfAnEmojiIsCutBeforeIt() {
        var question = "a".repeat(LeaseRetriever.MAX_QUERY_LENGTH - 1) + "😀 notice";

        retriever.retrieve(question, null);

        assertThat(fake.queryCalls.get(0)).isEqualTo("a".repeat(LeaseRetriever.MAX_QUERY_LENGTH - 1));
    }

    @Test
    void aBlankQuestionIsRejected() {
        for (var blank : new String[] {"", "   ", "\n\t", null}) {
            assertThatThrownBy(() -> retriever.retrieve(blank, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(fake.queryCalls).isEmpty();
    }

    @Test
    void noHostileQuestionThrowsAndOperatorsAreOnlyEverText() {
        for (var question : HostileQuestions.ALL) {
            assertThatCode(() -> retriever.retrieve(question, null)).as(question).doesNotThrowAnyException();
            assertThatCode(() -> retriever.retrieve(question, Set.of(document("en", 0).leaseId()), 8)).as(question)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void operatorsInAQuestionAreIgnoredAndItsWordsStillCount() {
        var lease = document("en", 0);

        var result = retriever.retrieve("(early) & termination | !fee <-> :*", Set.of(lease.leaseId()));

        assertThat(result.chunks().get(0).ref()).isEqualTo(refOf(lease, "Early termination"));
        assertThat(result.chunks().get(0).matchedBy()).contains(FULL_TEXT);
    }

    @Test
    void aQuestionWithNoWordsStillGetsTheNearestChunksByMeaning() {
        var result = retriever.retrieve("?!?!", null);

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.matchedBy()).containsExactly(VECTOR));
    }

    @Test
    void theTrigramThresholdDoesNotLeakToOtherQueriesOnTheConnection() {
        retriever.retrieve("terminaton fee", null);

        var threshold = jdbc.queryForObject("select current_setting('pg_trgm.word_similarity_threshold')",
                String.class);

        assertThat(threshold).isEqualTo("0.6");
    }
}
