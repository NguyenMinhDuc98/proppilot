package dev.proppilot.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimateTest {

    @Test
    void latinTextIsAboutFourCharactersToAToken() {
        assertThat(TokenEstimate.of("a".repeat(400))).isEqualTo(100);
        assertThat(TokenEstimate.of("")).isZero();
        assertThat(TokenEstimate.of("a")).isEqualTo(1);
    }

    @Test
    void arabicTextTakesMoreTokensThanLatinTextOfTheSameLength() {
        var arabic = "ا".repeat(300);
        var latin = "a".repeat(300);

        assertThat(TokenEstimate.of(arabic)).isEqualTo(100);
        assertThat(TokenEstimate.of(latin)).isEqualTo(75);
    }

    @Test
    void mixedTextIsTheSumOfItsParts() {
        assertThat(TokenEstimate.of("ا".repeat(30) + "a".repeat(40))).isEqualTo(10 + 10);
    }

    @Test
    void aSupplementaryCharacterCountsOnce() {
        assertThat(TokenEstimate.weight("😀")).isEqualTo(TokenEstimate.weight("a"));
    }
}
