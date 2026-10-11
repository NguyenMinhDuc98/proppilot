package dev.proppilot.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.rag.chunk.ClauseHeading.Heading;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClauseHeadingTest {

    @Test
    void anEnglishHeadingIsANumberAFullStopAndATitle() {
        assertThat(ClauseHeading.parse("7. Late payment")).contains(new Heading(7, "Late payment"));
        assertThat(ClauseHeading.parse("12. Governing law")).contains(new Heading(12, "Governing law"));
    }

    @Test
    void anArabicHeadingIsTheWordForClauseANumberAndATitle() {
        assertThat(ClauseHeading.parse("البند 7: غرامة التأخير")).contains(new Heading(7, "غرامة التأخير"));
        assertThat(ClauseHeading.parse("بند 3 - مدة العقد")).contains(new Heading(3, "مدة العقد"));
        assertThat(ClauseHeading.parse("البند 4 – مدة الإشعار")).contains(new Heading(4, "مدة الإشعار"));
    }

    @Test
    void arabicIndicAndPersianDigitsAreNumbers() {
        assertThat(ClauseHeading.parse("البند ٧: غرامة التأخير")).contains(new Heading(7, "غرامة التأخير"));
        assertThat(ClauseHeading.parse("البند ١٢: القانون الواجب التطبيق"))
                .contains(new Heading(12, "القانون الواجب التطبيق"));
        assertThat(ClauseHeading.parse("٩. Subletting")).contains(new Heading(9, "Subletting"));
    }

    @Test
    void aLatinTokenThatExtractionPutAtTheStartOfTheLineGoesBackAfterTheTitle() {
        assertThat(ClauseHeading.parse("A-504 البند 9: ملحق")).contains(new Heading(9, "ملحق A-504"));
        assertThat(ClauseHeading.parse("LSE-A-504 A-504 البند 10: ملحق")).contains(new Heading(10, "ملحق LSE-A-504 A-504"));
    }

    @Test
    void vowelMarksInTheTitleAreDropped() {
        assertThat(ClauseHeading.parse("البند 5: غَرامة التأخير")).contains(new Heading(5, "غرامة التأخير"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "7 days notice applies to the Tenant",
            "7.5 percent of the rent",
            "30. The Tenant shall pay the rent in advance.",
            "100. Too many digits",
            "0. Zero is not a clause",
            "البند 7 يسمح بالتجديد",
            "رقم العقد: LSE-A-504",
            "Landlord: PropPilot",
            "Page 2 of 3",
            ""})
    void anyOtherLineIsNotAHeading(String line) {
        assertThat(ClauseHeading.parse(line)).isEqualTo(Optional.empty());
    }

    @Test
    void aLineLongerThanAHeadingIsNeverOne() {
        var title = "x".repeat(ClauseHeading.MAX_LINE_LENGTH);

        assertThat(ClauseHeading.parse("7. " + title)).isEmpty();
        assertThat(ClauseHeading.parse("البند 7: " + title)).isEmpty();
    }
}
