package dev.proppilot.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Document text is not capped like a question is, and java.util.regex recurses once for each round of a repeated group,
 * so a long enough line in the wrong pattern overflows a small thread stack. Nothing in the chunker may depend on the
 * size or shape of the text: these run it on hostile text, on a thread with a 256 KB stack, a quarter of what the test
 * thread has. A recursion that needs more than that fails here whatever the size of the text.
 */
class LeaseChunkerHostileInputTest {

    private static final int HOSTILE = 200_000;
    private static final Duration LIMIT = Duration.ofSeconds(30);
    private static final long SMALL_STACK_BYTES = 256 * 1024;

    private final LeaseChunker chunker = new LeaseChunker();

    @Test
    void oneEndlessLineWithoutASpace() {
        assertWellFormed(chunk("1. Endless\n" + "ا".repeat(HOSTILE) + "\n"));
        assertWellFormed(chunk("Preamble\n" + "a".repeat(HOSTILE) + "\n"));
    }

    @Test
    void oneEndlessSentenceOfShortWords() {
        assertWellFormed(chunk("1. Words\n" + "word ".repeat(HOSTILE / 5) + "\n"));
        assertWellFormed(chunk("1. Arabic\n" + "كلمة ".repeat(HOSTILE / 5) + "\n"));
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
        assertWellFormed(chunk("1. " + "x".repeat(HOSTILE) + "\n"));
        assertWellFormed(chunk("البند 1: " + "ب".repeat(HOSTILE) + "\n"));
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
    void aLongRunOfHeadingsThatSkipAheadIsReadInOnePass() {
        assertWellFormed(chunk("1. Rent\n" + "3. Skipped\ntext\n".repeat(HOSTILE / 17)));
        assertWellFormed(chunk("1. Rent\n" + "3. Skipped\n".repeat(HOSTILE / 12) + "4. Next\n"));
    }

    @Test
    void veryManyShortLinesAndVeryManyPages() {
        assertWellFormed(chunk("x\n".repeat(HOSTILE)));
        var pages = IntStream.range(0, 5_000).mapToObj(page -> page + " x\nPage " + page + " of 5000\n").toList();
        assertWellFormed(chunk(pages));
    }

    private List<LeaseChunk> chunk(String page) {
        return chunk(List.of(page));
    }

    /** Chunks the pages on a thread with a small stack, and fails when that overflows or takes too long. */
    private List<LeaseChunk> chunk(List<String> pages) {
        var executor = Executors.newSingleThreadExecutor(
                work -> new Thread(null, work, "hostile-input", SMALL_STACK_BYTES));
        try {
            return executor.submit(() -> chunker.chunk("LSE-A-504", pages)).get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("Chunking failed on a " + SMALL_STACK_BYTES / 1024 + " KB stack", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Chunking took more than " + LIMIT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while chunking", e);
        } finally {
            executor.shutdownNow();
        }
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
