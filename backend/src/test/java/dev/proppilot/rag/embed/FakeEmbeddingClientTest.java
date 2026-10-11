package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FakeEmbeddingClientTest {

    private final FakeEmbeddingClient fake = new FakeEmbeddingClient(Map.of("vacate", "terminate"));

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    @Test
    void theSameWordsGiveTheSameVectorOfLengthOneWhateverTheSpelling() {
        var first = fake.vector("Late payment fee");
        var second = fake.vector("  fee, late PAYMENT!");

        assertThat(first).hasSize(FakeEmbeddingClient.DIMENSIONS).containsExactly(second);
        assertThat(cosine(first, first)).isCloseTo(1.0, offset(1e-6));
    }

    @Test
    void arabicVariantsOfALetterAndDiacriticsAreOneWord() {
        assertThat(fake.vector("غرامة التأخير")).containsExactly(fake.vector("غرامَه التاخير"));
    }

    @Test
    void textsWithWordsInCommonAreNearerThanTextsWithNone() {
        var question = fake.vector("notice period");

        assertThat(cosine(question, fake.vector("the notice period is 60 days")))
                .isGreaterThan(cosine(question, fake.vector("pets need written consent")));
    }

    @Test
    void anEquivalentWordIsTheSameAsTheWordItStandsFor() {
        assertThat(fake.vector("vacate")).containsExactly(fake.vector("terminate"));
    }

    @Test
    void aTextWithoutWordsStillHasAVectorOfLengthOne() {
        assertThat(cosine(fake.vector("?! ..."), fake.vector("?! ..."))).isCloseTo(1.0, offset(1e-6));
    }

    @Test
    void recordsItsCalls() {
        fake.embedDocuments(List.of("a", "b"));
        fake.embedQuery("q");

        assertThat(fake.documentCalls).containsExactly(List.of("a", "b"));
        assertThat(fake.queryCalls).containsExactly("q");
    }

    @Test
    void failsWhereAHookOrAScriptedFailureSaysAndForgetsItOnReset() {
        fake.beforeDocumentCall(number -> {
            if (number == 2) {
                throw new IllegalStateException("second call");
            }
        }).failQueriesWith(new IllegalStateException("queries"));

        fake.embedDocuments(List.of("a"));
        assertThatThrownBy(() -> fake.embedDocuments(List.of("b"))).hasMessage("second call");
        assertThatThrownBy(() -> fake.embedQuery("q")).hasMessage("queries");

        fake.reset();
        assertThat(fake.embedDocuments(List.of("c"))).hasSize(1);
        assertThat(fake.embedQuery("q")).hasSize(FakeEmbeddingClient.DIMENSIONS);
        assertThat(fake.documentCalls).hasSize(1);
    }
}
