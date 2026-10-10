package dev.proppilot.actions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlainTextTest {

    @Test
    void controlCharactersBecomeSpacesAndWhitespaceCollapses() {
        assertThat(PlainText.clean("  a\u0000b\tc\r\nd   e\u009f f ")).isEqualTo("a b c d e f");
    }

    @Test
    void leavesLettersOfAnyScriptAndPunctuationAlone() {
        assertThat(PlainText.clean("تسريب مياه في المطبخ، الوحدة A-203 (urgent)!"))
                .isEqualTo("تسريب مياه في المطبخ، الوحدة A-203 (urgent)!");
    }

    @Test
    void aTextOfOnlyControlCharactersIsEmpty() {
        assertThat(PlainText.clean("\n\t\u0007 ")).isEmpty();
    }
}
