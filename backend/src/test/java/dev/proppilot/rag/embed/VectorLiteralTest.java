package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VectorLiteralTest {

    @Test
    void writesTheNumbersInBracketsSeparatedByCommas() {
        assertThat(VectorLiteral.of(new float[] {0.5f, -1f, 0f})).isEqualTo("[0.5,-1.0,0.0]");
    }

    @Test
    void smallAndLargeNumbersKeepTheirValueInTheFormPgvectorReads() {
        assertThat(VectorLiteral.of(new float[] {1.0E-5f, 3.0E10f})).isEqualTo("[1.0E-5,3.0E10]");
    }

    @Test
    void anEmptyVectorIsEmptyBrackets() {
        assertThat(VectorLiteral.of(new float[0])).isEqualTo("[]");
    }
}
