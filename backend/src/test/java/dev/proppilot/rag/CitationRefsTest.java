package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.rag.CitationRefs.Ref;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CitationRefsTest {

    private static List<String> markersIn(String text) {
        var found = new ArrayList<String>();
        var matcher = CitationRefs.MARKER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void aWholeClauseIsTheDocumentCodeAHashAndTheClauseNumber() {
        assertThat(CitationRefs.format("LSE-A-504", 7)).isEqualTo("LSE-A-504#7");
        assertThat(CitationRefs.format("LSE-A-504", 0)).isEqualTo("LSE-A-504#0");
        assertThat(CitationRefs.format("LSE-F-102", 13)).isEqualTo("LSE-F-102#13");
    }

    @Test
    void thePartOfASplitClauseIsAddedAsLowercaseLetters() {
        assertThat(CitationRefs.format("LSE-A-504", 7, 0)).isEqualTo("LSE-A-504#7a");
        assertThat(CitationRefs.format("LSE-A-504", 13, 1)).isEqualTo("LSE-A-504#13b");
        assertThat(CitationRefs.format("LSE-A-504", 7, 25)).isEqualTo("LSE-A-504#7z");
    }

    @Test
    void partLettersGoOnAfterZ() {
        assertThat(CitationRefs.partLetters(0)).isEqualTo("a");
        assertThat(CitationRefs.partLetters(25)).isEqualTo("z");
        assertThat(CitationRefs.partLetters(26)).isEqualTo("aa");
        assertThat(CitationRefs.partLetters(27)).isEqualTo("ab");
        assertThat(CitationRefs.partLetters(701)).isEqualTo("zz");
        assertThat(CitationRefs.partLetters(702)).isEqualTo("aaa");
    }

    @Test
    void aPartThatNoRefCouldNameIsRefusedInsteadOfWrittenWithLettersNobodyCanRead() {
        assertThatThrownBy(() -> CitationRefs.partLetters(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CitationRefs.partLetters(26 + 676 + 17_576 + 456_976))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CitationRefs.partLetters(26 + 676 + 17_576 + 456_976 - 1)).isEqualTo("zzzz");
    }

    @Test
    void aMarkerIsTheRefInSquareBrackets() {
        assertThat(CitationRefs.marker("LSE-A-504#7a")).isEqualTo("[LSE-A-504#7a]");
    }

    @Test
    void parsesAWholeClauseAndAPart() {
        assertThat(CitationRefs.parse("LSE-A-504#7")).contains(new Ref("LSE-A-504", 7, ""));
        assertThat(CitationRefs.parse("LSE-A-504#0")).contains(new Ref("LSE-A-504", 0, ""));
        assertThat(CitationRefs.parse("LSE-B-312#13ab")).contains(new Ref("LSE-B-312", 13, "ab"));
    }

    @Test
    void whatIsFormattedIsParsedBackIntoTheSameParts() {
        for (int clause : new int[] {0, 1, 9, 10, 99}) {
            var whole = CitationRefs.parse(CitationRefs.format("LSE-C-205", clause)).orElseThrow();
            assertThat(whole).isEqualTo(new Ref("LSE-C-205", clause, ""));
            for (int part : new int[] {0, 1, 25, 26, 701, 702}) {
                var token = CitationRefs.format("LSE-C-205", clause, part);
                var parsed = CitationRefs.parse(token).orElseThrow();
                assertThat(parsed.documentCode()).isEqualTo("LSE-C-205");
                assertThat(parsed.clauseNumber()).isEqualTo(clause);
                assertThat(parsed.part()).isEqualTo(CitationRefs.partLetters(part));
                assertThat(parsed.token()).isEqualTo(token);
            }
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "LSE-A-504",
            "LSE-A-504#",
            "#7",
            "LSE-A-504#x",
            "LSE-A-504#-1",
            "LSE-A-504#07",
            "LSE-A-504#100",
            "LSE-A-504#7A",
            "LSE-A-504#7abcde",
            "LSE-A-504#7a1",
            "LSE-A-504#7 ",
            " LSE-A-504#7",
            "LSE-A-504#7\n",
            "[LSE-A-504#7]",
            "lse-a-504#7",
            "LSE-504#7",
            "LSE-A-#7",
            "LSE-A-504##7",
            "LSE-A-504#7#8",
            "LSE-A-504#٧",
            "LSE-A-504#7a, LSE-A-504#8",
            "XLSE-A-504#7",
            "LSE-A-504#7\u0000",
            "LSE-ABCDEFGHI-504#7",
            "LSE-A-123456#7"})
    void rejectsWhatIsNotARef(String malformed) {
        assertThat(CitationRefs.parse(malformed)).isEmpty();
    }

    @Test
    void aTextAsLongAsAnyHostileInputIsRejectedWithoutBeingLookedAt() {
        assertThat(CitationRefs.parse("LSE-A-504#7" + "a".repeat(100_000))).isEmpty();
        assertThat(CitationRefs.parse("LSE-" + "A".repeat(100_000))).isEmpty();
    }

    @Test
    void findsAMarkerInRunningText() {
        var text = "The late fee is 3% of the rent [LSE-A-504#5], and notice is 60 days [LSE-A-504#4].";

        assertThat(markersIn(text)).containsExactly("[LSE-A-504#5]", "[LSE-A-504#4]");
        var matcher = CitationRefs.MARKER.matcher(text);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo("LSE-A-504");
        assertThat(matcher.group(2)).isEqualTo("5");
        assertThat(matcher.group(3)).isNull();
    }

    @Test
    void findsMarkersThatStandNextToEachOther() {
        assertThat(markersIn("[LSE-A-504#7][LSE-A-504#8a][LSE-B-203#1]"))
                .containsExactly("[LSE-A-504#7]", "[LSE-A-504#8a]", "[LSE-B-203#1]");
        assertThat(markersIn("[LSE-A-504#7],[LSE-A-504#7a] [LSE-A-504#7b]"))
                .containsExactly("[LSE-A-504#7]", "[LSE-A-504#7a]", "[LSE-A-504#7b]");
    }

    @Test
    void aMarkerMustBeComplete() {
        assertThat(markersIn("[LSE-A-504#7")).isEmpty();
        assertThat(markersIn("LSE-A-504#7]")).isEmpty();
        assertThat(markersIn("(LSE-A-504#7)")).isEmpty();
        assertThat(markersIn("[LSE-A-504]")).isEmpty();
        assertThat(markersIn("[LSE-A-504#07]")).isEmpty();
        assertThat(markersIn("[ LSE-A-504#7 ]")).isEmpty();
        assertThat(markersIn("[LSE-A-504#7, LSE-A-504#8]")).isEmpty();
        assertThat(markersIn("[lse-a-504#7]")).isEmpty();
    }

    @Test
    void aMarkerInsideAnArabicSentenceIsFound() {
        var text = "غرامة التأخير 3% من الإيجار [LSE-A-504#5] لكل شهر.";

        assertThat(markersIn(text)).containsExactly("[LSE-A-504#5]");
    }

    @Test
    void aLongHostileTextIsSearchedWithoutOverflowingTheStack() {
        var hostile = "[LSE-A-504#7".repeat(20_000) + "[".repeat(50_000) + "[LSE-" + "A".repeat(50_000);

        assertThat(markersIn(hostile)).isEmpty();
        assertThat(markersIn("[LSE-A-504#7]".repeat(5_000))).hasSize(5_000);
    }

    @Test
    void quotedTextCanBeStrippedOfItsMarkersWithoutLosingTheWords() {
        assertThat(CitationRefs.withoutMarkers("No pets [LSE-A-504#7]. See [LSE-B-203#9a][LSE-B-203#10] and [x]."))
                .isEqualTo("No pets (LSE-A-504#7). See (LSE-B-203#9a)(LSE-B-203#10) and [x].");
        assertThat(CitationRefs.withoutMarkers("A $1 and \\ backslash [LSE-A-1#1]"))
                .isEqualTo("A $1 and \\ backslash (LSE-A-1#1)");
        assertThat(CitationRefs.withoutMarkers("no markers")).isEqualTo("no markers");
    }

    @Test
    void theDocumentCodePatternMatchesTheCodesOfTheDocuments() {
        assertThat("LSE-A-504").matches(CitationRefs.DOCUMENT_CODE);
        assertThat("LSE-F-102").matches(CitationRefs.DOCUMENT_CODE);
        assertThat("A-504").doesNotMatch(CitationRefs.DOCUMENT_CODE);
        assertThat("LSE-A-504#7").doesNotMatch(CitationRefs.DOCUMENT_CODE);
    }
}
