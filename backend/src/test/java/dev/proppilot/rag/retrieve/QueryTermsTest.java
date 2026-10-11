package dev.proppilot.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QueryTermsTest {

    @Test
    void wordsAreFoldedLikeTheStoredChunks() {
        var terms = QueryTerms.of("Notice PERIOD for the Tenant");

        assertThat(terms.text()).isEqualTo("notice period for the tenant");
    }

    @Test
    void arabicLettersAreOneSpellingAndDiacriticsAreGone() {
        var terms = QueryTerms.of("غَرَامَةُ التَّأْخِيرِ على الإيجار");

        assertThat(terms.text()).isEqualTo("غرامه التاخير علي الايجار");
    }

    @Test
    void wordsInArabicScriptAndTheOthersGoToTheirOwnConfiguration() {
        var terms = QueryTerms.of("notice فترة الإشعار 60 days");

        assertThat(terms.arabicTsQuery()).isEqualTo("فتره | الاشعار");
        assertThat(terms.otherTsQuery()).isEqualTo("notice | 60 | days");
    }

    @Test
    void nothingButLettersAndDigitsSurvives() {
        var terms = QueryTerms.of("a&b | !c <-> (d):* 'e' \"f\" \\g; DROP TABLE x -- ; \u0000 \t\n end.");

        assertThat(terms.text()).isEqualTo("drop table end");
        assertThat(terms.arabicTsQuery() + terms.otherTsQuery()).matches("[\\p{L}\\p{N} |]*");
    }

    @Test
    void aWordWithAHyphenOrAnApostropheIsTwoWords() {
        assertThat(QueryTerms.of("A-504 tenant's").text()).isEqualTo("504 tenant");
    }

    @Test
    void aSingleLetterIsDroppedAndASingleDigitIsKept() {
        assertThat(QueryTerms.of("clause 7 a b").text()).isEqualTo("clause 7");
    }

    @Test
    void aWordIsKeptOnce() {
        assertThat(QueryTerms.of("fee fee FEE Fee").text()).isEqualTo("fee");
    }

    @Test
    void atMostThirtyTwoDifferentWordsAreKept() {
        var question = IntStream.range(0, 100).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));

        var terms = QueryTerms.of(question);

        assertThat(terms.text().split(" ")).hasSize(QueryTerms.MAX_TERMS).startsWith("word0", "word1");
    }

    @Test
    void aWordLongerThanAnyRealWordIsDropped() {
        assertThat(QueryTerms.of("a".repeat(65) + " notice").text()).isEqualTo("notice");
        assertThat(QueryTerms.of("a".repeat(64)).text()).hasSize(64);
    }

    @Test
    void aQuestionWithNoWordsIsEmpty() {
        for (var question : new String[] {"", "   ", "!!! ??? ...", "؟ ، ؛", "😀😀😀", "a b c", "\u0000", "&|!():*<->"}) {
            var terms = QueryTerms.of(question);

            assertThat(terms.isEmpty()).as(question).isTrue();
            assertThat(terms.arabicTsQuery()).isEmpty();
            assertThat(terms.otherTsQuery()).isEmpty();
            assertThat(terms.text()).isEmpty();
        }
    }
}
