package dev.proppilot.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SentencesTest {

    @Test
    void cutsAfterAStopFollowedByWhiteSpace() {
        assertThat(Sentences.split("One. Two! Three? Four"))
                .containsExactly("One.", "Two!", "Three?", "Four");
    }

    @Test
    void aStopInsideANumberOrACodeEndsNothing() {
        assertThat(Sentences.split("The fee is SAR 247.50 per month. See A-504."))
                .containsExactly("The fee is SAR 247.50 per month.", "See A-504.");
    }

    @Test
    void cutsArabicSentences() {
        assertThat(Sentences.split("يدفع المستأجر الإيجار. هل يتأخر؟ لا يتأخر"))
                .containsExactly("يدفع المستأجر الإيجار.", "هل يتأخر؟", "لا يتأخر");
    }

    @Test
    void aRunOfStopsAndClosingQuotesStaysWithItsSentence() {
        assertThat(Sentences.split("Really?! (Yes.) \"Done.\" Next"))
                .containsExactly("Really?!", "(Yes.)", "\"Done.\"", "Next");
    }

    @Test
    void textWithoutAStopIsOneSentenceAndNothingIsNone() {
        assertThat(Sentences.split("no stop here")).containsExactly("no stop here");
        assertThat(Sentences.split("   ")).isEmpty();
        assertThat(Sentences.split("")).isEmpty();
    }
}
