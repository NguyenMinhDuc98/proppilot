package dev.proppilot.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LeaseChunkerTest {

    private static final String CODE = "LSE-A-504";

    private final LeaseChunker chunker = new LeaseChunker();

    @Test
    void anEnglishDocumentBecomesAPreambleAndOneChunkForEachClause() {
        var chunks = chunker.chunk(CODE, List.of(
                """
                Residential Lease Agreement – Unit A-504
                Contract No. LSE-A-504
                Parties
                Landlord: PropPilot Demo Properties Co.
                1. Rent and payment
                The Tenant shall pay SAR 4950 on the first day of each month.
                Page 1 of 2
                """,
                """
                2. Late payment
                A late fee applies after 5 days.
                3. Pets
                No pets may be kept in the Property.
                Signatures
                Landlord: PropPilot Demo Properties Co.
                Signature: ______________________   Date: ______________
                Page 2 of 2
                """));

        assertThat(chunks).extracting(LeaseChunk::ref)
                .containsExactly("LSE-A-504#0", "LSE-A-504#1", "LSE-A-504#2", "LSE-A-504#3");
        assertThat(chunks).extracting(LeaseChunk::ordinal).containsExactly(0, 1, 2, 3);
        assertThat(chunks).extracting(LeaseChunk::sectionNo).containsExactly(null, 1, 2, 3);
        assertThat(chunks).extracting(LeaseChunk::sectionTitle)
                .containsExactly(null, "Rent and payment", "Late payment", "Pets");
        assertThat(chunks).extracting(LeaseChunk::page).containsExactly(1, 1, 2, 2);
        assertThat(chunks.get(0).content()).isEqualTo(
                "Residential Lease Agreement – Unit A-504 Contract No. LSE-A-504 Parties "
                        + "Landlord: PropPilot Demo Properties Co.");
        assertThat(chunks.get(1).content())
                .isEqualTo("1. Rent and payment\nThe Tenant shall pay SAR 4950 on the first day of each month.");
    }

    @Test
    void pageFootersAndTheSignatureBlockAreNotPartOfAnyChunk() {
        var chunks = chunker.chunk(CODE, List.of(
                "1. Rent\nPay on time.\nPage 1 of 2\n",
                "2. Pets\nNo pets.\nSignatures\nLandlord: X\nSignature: ____\nPage 2 of 2\n"));

        assertThat(chunks).extracting(LeaseChunk::content)
                .containsExactly("1. Rent\nPay on time.", "2. Pets\nNo pets.");
    }

    @Test
    void anArabicFooterIsDroppedWhateverOrderExtractionPutItsWordsIn() {
        var chunks = chunker.chunk(CODE, List.of("البند 1: الإيجار\nيدفع الإيجار.\nصفحة 1 من 2\n",
                "البند 2: الصيانة\nعلى المؤجر.\n3 من 2 صفحة\n"));

        assertThat(chunks).extracting(LeaseChunk::content)
                .containsExactly("البند 1: الإيجار\nيدفع الإيجار.", "البند 2: الصيانة\nعلى المؤجر.");
    }

    @Test
    void anArabicDocumentIsCutAtClausesWithArabicIndicDigits() {
        var chunks = chunker.chunk(CODE, List.of(
                "عقد إيجار سكني\nالأطراف\nالمؤجر: شركة\nالبند ١: الإيجار وطريقة السداد\nيدفع المستأجر ٤٩٥٠ ريال.\n"
                        + "البند ٧: غرامة التأخير\nتستحق غرامة بعد ٥ أيام.\n"));

        assertThat(chunks).extracting(LeaseChunk::ref).containsExactly("LSE-A-504#0", "LSE-A-504#1", "LSE-A-504#7");
        assertThat(chunks.get(2).sectionNo()).isEqualTo(7);
        assertThat(chunks.get(2).sectionTitle()).isEqualTo("غرامة التأخير");
        assertThat(chunks.get(2).content()).isEqualTo("البند ٧: غرامة التأخير\nتستحق غرامة بعد ٥ أيام.");
    }

    @Test
    void aLatinTokenAtTheStartOfARightToLeftLineDoesNotHideAHeadingOrTheUnitCode() {
        var chunks = chunker.chunk(CODE, List.of(
                "A-504 عقد إيجار سكني – الوحدة\nLSE-A-504 :رقم العقد\nA-504 البند 1: ملحق\nيسري على الوحدة A-504 فقط.\n"));

        assertThat(chunks).extracting(LeaseChunk::ref).containsExactly("LSE-A-504#0", "LSE-A-504#1");
        assertThat(chunks.get(0).searchText()).contains("a-504", "lse-a-504");
        assertThat(chunks.get(1).sectionTitle()).isEqualTo("ملحق A-504");
        assertThat(chunks.get(1).searchText()).contains("a-504");
    }

    @Test
    void aClauseNumberMustBeHigherThanTheOneBeforeSoParagraphsDoNotMakeClauses() {
        var chunks = chunker.chunk(CODE, List.of(
                "1. Rent\nPay on the first.\n2. Notice\nThe notice period is below.\n"
                        + "1. Thirty days for the Tenant\n2. Sixty days for the Landlord\n3. Pets\nNo pets.\n"));

        assertThat(chunks).extracting(LeaseChunk::sectionNo).containsExactly(1, 2, 3);
        assertThat(chunks.get(1).content()).contains("1. Thirty days for the Tenant", "2. Sixty days for the Landlord");
    }

    @Test
    void aClauseWithoutAnyTextIsStillAChunk() {
        var chunks = chunker.chunk(CODE, List.of("1. Rent\n2. Pets\nNo pets.\n"));

        assertThat(chunks).extracting(LeaseChunk::content).containsExactly("1. Rent", "2. Pets\nNo pets.");
    }

    @Test
    void textWithNoClausesIsOnePreambleChunk() {
        var chunks = chunker.chunk(CODE, List.of("Just a note.\nWith two lines.\n"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).ref()).isEqualTo("LSE-A-504#0");
        assertThat(chunks.get(0).sectionNo()).isNull();
        assertThat(chunks.get(0).content()).isEqualTo("Just a note. With two lines.");
    }

    @Test
    void anEmptyDocumentHasNoChunks() {
        assertThat(chunker.chunk(CODE, List.of())).isEmpty();
        assertThat(chunker.chunk(CODE, List.of("", " \n \n", "Page 1 of 1\n"))).isEmpty();
    }

    @Test
    void searchTextIsTheContentFoldedAndMatchesTheTokenEstimate() {
        var chunks = chunker.chunk(CODE,
                List.of("البند ١: الإيجــار\nيَدفع المُستأجر ٤٩٥٠ ريال للوحدة A-504 في المؤجَّر.\n"));

        var chunk = chunks.get(0);

        assertThat(chunk.content()).contains("الإيجار").doesNotContain("ـ");
        assertThat(chunk.searchText()).isEqualTo(Folding.fold(Folding.withoutDiacritics(chunk.content())));
        assertThat(chunk.searchText()).contains("الايجار", "يدفع المستاجر", "4950", "a-504", "الموجر");
        assertThat(chunk.tokenEstimate()).isEqualTo(TokenEstimate.of(chunk.content()));
    }

    @Test
    void controlCharactersAndRunsOfWhiteSpaceAreCleanedBeforeAnythingIsStored() {
        var chunks = chunker.chunk(CODE, List.of("1.   Rent\n  Pay\u0000 on\t\tthe first ‏ day.  \n"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("1. Rent\nPay on the first day.");
    }

    @Test
    void aLongClauseIsSplitOnSentencesIntoLetteredPartsThatEachStartWithTheHeading() {
        var sentences = numberedSentences(60);
        var chunks = chunker.chunk(CODE, List.of("1. Rent\nPay.\n7. House rules\n" + String.join(" ", sentences) + "\n"));

        var parts = chunks.stream().filter(chunk -> chunk.sectionNo() != null && chunk.sectionNo() == 7).toList();

        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(parts).extracting(LeaseChunk::ref).startsWith("LSE-A-504#7a", "LSE-A-504#7b");
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.content()).startsWith("7. House rules\n");
            assertThat(part.tokenEstimate()).isLessThanOrEqualTo(LeaseChunker.MAX_CHUNK_TOKENS);
            assertThat(part.sectionTitle()).isEqualTo("House rules");
        });
        assertThat(chunks.stream().map(LeaseChunk::ordinal)).containsExactlyElementsOf(
                IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void eachPartStartsWithTheEndOfThePartBeforeItAndNoSentenceIsLost() {
        var sentences = numberedSentences(120);
        var chunks = chunker.chunk(CODE, List.of("7. House rules\n" + String.join(" ", sentences) + "\n"));
        var bodies = chunks.stream().map(chunk -> sentencesOf(chunk.content())).toList();

        assertThat(bodies).hasSizeGreaterThanOrEqualTo(3);
        var seen = new ArrayList<>(bodies.get(0));
        for (int part = 1; part < bodies.size(); part++) {
            var previous = bodies.get(part - 1);
            var current = bodies.get(part);
            int overlap = overlap(previous, current);

            assertThat(overlap).as("part %d repeats the end of part %d", part, part - 1).isPositive();
            assertThat(TokenEstimate.of(String.join(" ", current.subList(0, overlap))))
                    .isLessThanOrEqualTo(LeaseChunker.OVERLAP_TOKENS);
            seen.addAll(current.subList(overlap, current.size()));
        }
        assertThat(seen).isEqualTo(sentences);
    }

    @Test
    void partRefsAreStableForTheSameText() {
        var pages = List.of("7. House rules\n" + String.join(" ", numberedSentences(80)) + "\n");

        assertThat(chunker.chunk(CODE, pages)).isEqualTo(chunker.chunk(CODE, pages));
    }

    @Test
    void aSentenceLongerThanAPartIsCutBetweenWordsAndAWordWithoutBreaksIsCutAnywhere() {
        var words = IntStream.range(0, 2000).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));
        var chunks = chunker.chunk(CODE, List.of("1. Long\n" + words + "\n2. Unbroken\n" + "x".repeat(9000) + "\n"));

        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenEstimate())
                .isLessThanOrEqualTo(LeaseChunker.MAX_CHUNK_TOKENS));
        int unbrokenLength = chunks.stream().filter(chunk -> chunk.sectionNo() != null && chunk.sectionNo() == 2)
                .mapToInt(chunk -> chunk.content().length() - "2. Unbroken\n".length()).sum();
        assertThat(unbrokenLength).isGreaterThanOrEqualTo(9000);
    }

    @Test
    void partLettersGoOnAfterZ() {
        assertThat(LeaseChunker.letters(0)).isEqualTo("a");
        assertThat(LeaseChunker.letters(25)).isEqualTo("z");
        assertThat(LeaseChunker.letters(26)).isEqualTo("aa");
        assertThat(LeaseChunker.letters(27)).isEqualTo("ab");
        assertThat(LeaseChunker.letters(701)).isEqualTo("zz");
        assertThat(LeaseChunker.letters(702)).isEqualTo("aaa");
    }

    @Test
    void refsAreUniqueWithinADocument() {
        var pages = List.of("Preamble.\n1. A\n" + String.join(" ", numberedSentences(70)) + "\n2. B\nText.\n3. C\n"
                + String.join(" ", numberedSentences(70)) + "\n");

        var refs = chunker.chunk(CODE, pages).stream().map(LeaseChunk::ref).toList();

        assertThat(refs).doesNotHaveDuplicates();
    }

    /** "Sentence 17 says that the Tenant shall pay the rent on time and keep the Property in good order." */
    private static List<String> numberedSentences(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(n -> ("Sentence %d says that the Tenant shall pay the rent on time and keep the Property "
                        + "in good order.").formatted(n))
                .toList();
    }

    private static List<String> sentencesOf(String content) {
        var body = content.substring(content.indexOf('\n') + 1);
        return List.of(body.split("(?<=\\.) "));
    }

    /** How many sentences at the start of {@code current} are the last sentences of {@code previous}. */
    private static int overlap(List<String> previous, List<String> current) {
        int best = 0;
        for (int count = 1; count <= Math.min(previous.size(), current.size()); count++) {
            if (previous.subList(previous.size() - count, previous.size()).equals(current.subList(0, count))) {
                best = count;
            }
        }
        return best;
    }
}
