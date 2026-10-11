package dev.proppilot.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.text.Folding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LeaseTopicTest {

    /** What is left of the text, with its gaps closed. */
    private static String leftOf(String text) {
        return LeaseTopic.LATE_FEE.without(Folding.fold(text)).replaceAll("\\s+", " ").strip();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "what is the late fee in the lease|what is the in the lease",
            "what is the late payment fee in the lease|what is the in the lease",
            "what are the fees for late payment in it|what are the in it",
            "who is late on rent and what is the late fee|who is late on rent and what is the",
            "ما غرامة التأخير في السداد في العقد|ما في العقد",
            "ما غرامة التأخير في العقد|ما في العقد"})
    void takesTheWordsOfTheLateFeeClauseOutAndLeavesTheRest(String text, String expected) {
        assertThat(leftOf(text)).isEqualTo(Folding.fold(expected));
    }

    @Test
    void leavesTextThatDoesNotNameTheTopicAsItIs() {
        assertThat(LeaseTopic.LATE_FEE.without("who is overdue on rent?")).isEqualTo("who is overdue on rent?");
    }

    @Test
    void theLateFeePhraseIsStillTheLateFeeTopicWithItsTrailingPaymentWords() {
        assertThat(LeaseTopic.in("what are the fees for late payment")).containsExactly(LeaseTopic.LATE_FEE);
        assertThat(LeaseTopic.in(Folding.fold("ما غرامة التأخير في السداد"))).containsExactly(LeaseTopic.LATE_FEE);
    }
}
