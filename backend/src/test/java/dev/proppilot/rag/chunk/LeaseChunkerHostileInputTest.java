package dev.proppilot.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Document text is not capped like a question is, and java.util.regex recurses once for each round of a repeated group,
 * so a long enough line in the wrong pattern overflows a small thread stack. Nothing in the chunker may depend on the
 * size or shape of the text: these run it on hostile text, and CI proves the margin by running them with a 256 KB
 * thread stack ({@code -DargLine=-Xss256k}).
 */
class LeaseChunkerHostileInputTest {

    private static final int HOSTILE = 200_000;
    private static final Duration LIMIT = Duration.ofSeconds(30);

    private final LeaseChunker chunker = new LeaseChunker();

    @Test
    void oneEndlessLineWithoutASpace() {
        assertWellFormed(chunk("7. Endless\n" + "ا".repeat(HOSTILE) + "\n"));
        assertWellFormed(chunk("Preamble\n" + "a".repeat(HOSTILE) + "\n"));
    }

    @Test
    void aLongRunOfPunctuation() {
        assertWellFormed(chunk("1. Dots\n" + ".".repeat(HOSTILE) + "\n"));
        assertWellFormed(chunk("1. Spaced\n" + "! ".repeat(HOSTILE / 2) + "\n"));
        assertWellFormed(chunk("1. Arabic\n" + "؟ ".repeat(HOSTILE / 2) + "\n"));
        assertWellFormed(chunk("1. Quotes\n" + ".\") ".repeat(HOSTILE / 4) + "\n"));
    }

    @Test
    void aHeadingFollowedByTwoHundredThousandCharacters() {
        assertWellFormed(chunk("7. " + "x".repeat(HOSTILE) + "\n"));
        assertWellFormed(chunk("البند 7: " + "ب".repeat(HOSTILE) + "\n"));
        assertWellFormed(chunk("البند 1: ".repeat(HOSTILE / 9) + "\n"));
    }

    @Test
    void latinTokensAndSeparatorsRepeatedOnOneLine() {
        assertWellFormed(chunk("a-1 ".repeat(HOSTILE / 4) + "البند 1: x\n"));
        assertWellFormed(chunk("1 ".repeat(HOSTILE / 2) + "\n"));
        assertWellFormed(chunk("البند " + "٧".repeat(HOSTILE) + ": x\n"));
    }

    @Test
    void textThatBackTracksInANaiveAlternation() {
        assertWellFormed(chunk("1. Alternation\n" + "ab".repeat(HOSTILE / 2) + "!\n"));
        assertWellFormed(chunk("1. Brackets\n" + "(".repeat(HOSTILE / 2) + ")".repeat(HOSTILE / 2) + "\n"));
        assertWellFormed(chunk("1. Whitespace\n" + "a" + " ".repeat(HOSTILE) + "b\n"));
    }

    @Test
    void headingLikeLinesAreNumberedOnceAndTheRestIsBody() {
        var lines = IntStream.rangeClosed(1, 99).mapToObj(n -> n + ". Clause " + n)
                .collect(Collectors.joining("\n"));
        var chunks = chunk(lines + "\n" + "1. again\n".repeat(HOSTILE / 9));

        assertWellFormed(chunks);
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(99);
        assertThat(chunks.stream().map(LeaseChunk::sectionNo).distinct().count()).isEqualTo(99);
    }

    @Test
    void veryManyShortLinesAndVeryManyPages() {
        assertWellFormed(chunk("x\n".repeat(HOSTILE)));
        var pages = IntStream.range(0, 5_000).mapToObj(page -> page + " x\nPage " + page + " of 5000\n").toList();
        assertWellFormed(assertTimeout(LIMIT, () -> chunker.chunk("LSE-A-504", pages)));
    }

    private List<LeaseChunk> chunk(String page) {
        return assertTimeout(LIMIT, () -> chunker.chunk("LSE-A-504", List.of(page)));
    }

    private static void assertWellFormed(List<LeaseChunk> chunks) {
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenEstimate())
                .isLessThanOrEqualTo(LeaseChunker.MAX_CHUNK_TOKENS));
        assertThat(chunks.stream().map(LeaseChunk::ref).distinct().count()).isEqualTo(chunks.size());
        assertThat(chunks.stream().map(LeaseChunk::ordinal).toList())
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }
}
