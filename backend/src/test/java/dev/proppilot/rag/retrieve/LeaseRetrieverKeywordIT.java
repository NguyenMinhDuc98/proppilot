package dev.proppilot.rag.retrieve;

import static dev.proppilot.rag.retrieve.CandidateList.FULL_TEXT;
import static dev.proppilot.rag.retrieve.CandidateList.TRIGRAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.rag.CorpusIntegrationTest;
import dev.proppilot.rag.DocumentLanguage;
import dev.proppilot.rag.embed.EmbeddingClient;
import dev.proppilot.rag.embed.NoEmbeddingClient;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Retrieval as the application runs with no embedding provider: full text and trigrams alone, at no cost. */
class LeaseRetrieverKeywordIT extends CorpusIntegrationTest {

    @Autowired
    LeaseRetriever retriever;
    @Autowired
    EmbeddingClient embeddings;

    private long leaseOf(String language) {
        return jdbc.queryForObject(
                "select lease_id from lease_documents where language = ? order by lease_id limit 1", Long.class,
                language);
    }

    private String refOf(long leaseId, String sectionTitle) {
        return jdbc.queryForObject("""
                select c.ref from lease_chunks c join lease_documents d on d.id = c.document_id
                where d.lease_id = ? and c.section_title = ? order by c.ordinal limit 1""", String.class, leaseId,
                sectionTitle);
    }

    @Test
    void theApplicationRunsWithoutAnEmbeddingProviderByDefault() {
        assertThat(embeddings).isInstanceOf(NoEmbeddingClient.class);
        assertThat(embeddings.enabled()).isFalse();
    }

    @Test
    void anEnglishQuestionFindsTheEnglishClauseByKeywords() {
        long lease = leaseOf("en");

        var result = retriever.retrieve("what is the late payment fee?", Set.of(lease));

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
        var top = result.chunks().get(0);
        assertThat(top.ref()).isEqualTo(refOf(lease, "Late payment"));
        assertThat(top.language()).isEqualTo(DocumentLanguage.EN);
        assertThat(top.matchedBy()).containsExactly(FULL_TEXT, TRIGRAM);
        assertThat(result.chunks()).hasSize(4);
    }

    @Test
    void anEnglishQuestionFindsTheNoticeClauseOfAnArabicDocumentByItsTopic() {
        long lease = leaseOf("ar");

        var result = retriever.retrieve("What notice period does the lease require?", Set.of(lease));

        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.chunks()).isNotEmpty();
        var top = result.chunks().get(0);
        assertThat(top.language()).isEqualTo(DocumentLanguage.AR);
        assertThat(top.ref()).isEqualTo(refOf(lease, "مدة الإشعار"));
    }

    @Test
    void anArabicQuestionFindsTheNoticeClauseOfAnEnglishDocumentByItsTopic() {
        long lease = leaseOf("en");

        var result = retriever.retrieve("ما هي مدة الإشعار في العقد؟", Set.of(lease));

        assertThat(result.chunks()).isNotEmpty();
        var top = result.chunks().get(0);
        assertThat(top.language()).isEqualTo(DocumentLanguage.EN);
        assertThat(top.ref()).isEqualTo(refOf(lease, "Notice period"));
    }

    @Test
    void aQuestionWithNoLeaseTopicIsSearchedAsItWasAsked() {
        long lease = leaseOf("ar");

        var result = retriever.retrieve("completely unrelated wording about nothing", Set.of(lease));

        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void theClauseWhoseTitleNamesTheTopicOutranksOneThatOnlyMentionsIt() {
        long lease = leaseOf("en");
        // A bloated question, as a model writes it: the early termination clause says "notice" as often as the notice
        // period clause, but only one of them is titled "Notice period".
        var question = "فترة الإشعار notice period notification days advance warning";

        var result = retriever.retrieve(question, Set.of(lease), 2);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().get(0).ref()).isEqualTo(refOf(lease, "Notice period"));
    }

    @Test
    void theArabicClauseWhoseTitleNamesTheTopicOutranksOneThatOnlyMentionsIt() {
        long lease = leaseOf("ar");

        var result = retriever.retrieve("ما فترة الإشعار notice period days advance warning", Set.of(lease), 2);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().get(0).ref()).isEqualTo(refOf(lease, "مدة الإشعار"));
    }

    @Test
    void anEnglishWordIsFoundByItsStem() {
        long lease = leaseOf("en");

        var result = retriever.retrieve("terminating the lease early", Set.of(lease));

        assertThat(result.chunks().get(0).ref()).isEqualTo(refOf(lease, "Early termination"));
        assertThat(result.chunks().get(0).matchedBy()).contains(FULL_TEXT);
    }

    @Test
    void aMisspelledWordIsFoundByTrigramSimilarity() {
        long lease = leaseOf("en");

        var result = retriever.retrieve("terminaton", Set.of(lease));

        var termination = result.chunks().stream()
                .filter(chunk -> chunk.ref().equals(refOf(lease, "Early termination"))).findFirst();
        assertThat(termination).as("the clause the misspelled word means").isPresent();
        assertThat(termination.get().matchedBy()).contains(TRIGRAM);
    }

    @Test
    void anArabicQuestionFindsTheArabicClause() {
        long lease = leaseOf("ar");

        var result = retriever.retrieve("ما هي غرامة التأخير؟", Set.of(lease));

        var top = result.chunks().get(0);
        assertThat(top.ref()).isEqualTo(refOf(lease, "غرامة التأخير"));
        assertThat(top.language()).isEqualTo(DocumentLanguage.AR);
        assertThat(top.matchedBy()).contains(FULL_TEXT);
    }

    @Test
    void anArabicQuestionFindsTheClauseWhateverTheSpellingOfAlefYaAndTaMarbutaAndTheDiacritics() {
        long lease = leaseOf("ar");
        var expected = refOf(lease, "غرامة التأخير");
        var variants = List.of(
                "غرامة التأخير",
                "غرامة التاخير",
                "غرامه التأخير",
                "غرامة التأخىر",
                "غَرَامَةُ التَّأْخِيرِ",
                "غرامــــة التأخير",
                "غـَرامة الـتّأخير");

        for (var question : variants) {
            var result = retriever.retrieve(question, Set.of(lease), 1);

            assertThat(result.chunks()).as(question).extracting(RetrievedChunk::ref).containsExactly(expected);
        }
    }

    @Test
    void theYaAndAlefVariantsOfOtherWordsMatchToo() {
        long lease = leaseOf("ar");

        assertThat(retriever.retrieve("الحىوانات الاليفة", Set.of(lease), 1).chunks().get(0).ref())
                .isEqualTo(refOf(lease, "الحيوانات الأليفة"));
        assertThat(retriever.retrieve("الانهاء المبكر", Set.of(lease), 1).chunks().get(0).ref())
                .isEqualTo(refOf(lease, "الإنهاء المبكر"));
        assertThat(retriever.retrieve("المعاينه ودخول العقار", Set.of(lease), 1).chunks().get(0).ref())
                .isEqualTo(refOf(lease, "المعاينة ودخول العقار"));
    }

    @Test
    void anEnglishQuestionDoesNotMatchEveryChunkThroughItsStopWords() {
        long lease = leaseOf("en");

        var result = retriever.retrieve("the of is a to in for", Set.of(lease), 8);

        assertThat(result.chunks().stream().filter(chunk -> chunk.matchedBy().contains(FULL_TEXT))).isEmpty();
    }

    @Test
    void aLeaseFilterExcludesTheOtherLeases() {
        long english = leaseOf("en");

        var result = retriever.retrieve("notice period", Set.of(english), 8);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).extracting(RetrievedChunk::documentCode).containsOnly(
                jdbc.queryForObject("select code from lease_documents where lease_id = ?", String.class, english));
        assertThat(retriever.retrieve("notice period", null, 8).chunks().stream()
                .map(RetrievedChunk::documentCode).distinct()).as("without a filter, other leases answer too")
                .hasSizeGreaterThan(1);
    }

    @Test
    void topKIsFourByDefaultAndKeptBetweenOneAndEight() {
        assertThat(retriever.retrieve("notice period", null).chunks()).hasSize(4);
        assertThat(retriever.retrieve("notice period", null, 1).chunks()).hasSize(1);
        assertThat(retriever.retrieve("notice period", null, 8).chunks()).hasSize(8);
        assertThat(retriever.retrieve("notice period", null, 0).chunks()).hasSize(1);
        assertThat(retriever.retrieve("notice period", null, 99).chunks()).hasSize(8);
    }

    @Test
    void aQuestionNoChunkIsAnythingLikeFindsNothing() {
        var result = retriever.retrieve("xylophone quokka zeppelin", null);

        assertThat(result.chunks()).isEmpty();
        assertThat(result.retrieval()).isEqualTo(RetrievalMode.KEYWORD);
    }

    @Test
    void noHostileQuestionThrows() {
        long lease = leaseOf("ar");

        for (var question : HostileQuestions.ALL) {
            assertThatCode(() -> retriever.retrieve(question, null)).as(question).doesNotThrowAnyException();
            assertThatCode(() -> retriever.retrieve(question, Set.of(lease), 8)).as(question)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void aBlankQuestionIsRejected() {
        assertThatThrownBy(() -> retriever.retrieve("  ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVeryLongQuestionOfDifferentWordsIsAnsweredOnTheFirstFiveHundredCharacters() {
        var question = "notice " + "w1234567 ".repeat(200);

        assertThatCode(() -> retriever.retrieve(question, null)).doesNotThrowAnyException();
        assertThat(retriever.retrieve(question, null).chunks()).isNotEmpty();
    }
}
