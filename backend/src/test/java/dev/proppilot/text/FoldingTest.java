package dev.proppilot.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FoldingTest {

    @Test
    void vowelMarksAndTatweelAreTakenOut() {
        assertThat(Folding.withoutDiacritics("يَوْمًا")).isEqualTo("يوما");
        assertThat(Folding.withoutDiacritics("الإيجــــار")).isEqualTo("الإيجار");
    }

    @Test
    void oneSpellingForEachArabicLetterThatHasSeveral() {
        assertThat(Folding.fold("أإآ")).isEqualTo("ااا");
        assertThat(Folding.fold("على")).isEqualTo(Folding.fold("علي"));
        assertThat(Folding.fold("غرامة")).isEqualTo(Folding.fold("غرامه"));
        assertThat(Folding.fold("مؤجر")).isEqualTo("موجر");
    }

    @Test
    void digitsAreAsciiAndLatinLettersAreLowerCase() {
        assertThat(Folding.fold("الوحدة A-٥٠٤، ۱۲")).isEqualTo("الوحده a-504، 12");
    }

    @Test
    void foldedTextIsAsLongAsTheTextItCameFrom() {
        var text = "البند ٧: غرامة التأخير A-504 — Late payment";

        assertThat(Folding.fold(text)).hasSameSizeAs(text);
    }
}
