package dev.proppilot.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * A question is capped at 500 characters where it comes in, but the words of one are cut out of whatever text this
 * class is given. These run it on hostile text of a megabyte, on a thread with a 256 KB stack, a quarter of what a
 * test thread has: a recursion that needs more than that fails here whatever the size of the text.
 */
class QueryTermsHostileInputTest {

    private static final int HOSTILE = 1_000_000;
    private static final long SMALL_STACK_BYTES = 256 * 1024;

    @Test
    void oneEndlessWord() {
        assertThat(onSmallStack("a".repeat(HOSTILE)).isEmpty()).isTrue();
        assertThat(onSmallStack("ا".repeat(HOSTILE)).isEmpty()).isTrue();
    }

    @Test
    void anEndlessRunOfPunctuationAndOperators() {
        assertThat(onSmallStack(".".repeat(HOSTILE)).isEmpty()).isTrue();
        assertThat(onSmallStack("&|!():*<->'\\\"".repeat(HOSTILE / 12)).isEmpty()).isTrue();
        assertThat(onSmallStack("؟ ".repeat(HOSTILE / 2)).isEmpty()).isTrue();
    }

    @Test
    void anEndlessRunOfShortWords() {
        var terms = onSmallStack("word ".repeat(HOSTILE / 5));

        assertThat(terms.text()).isEqualTo("word");
    }

    @Test
    void manyDifferentWordsAreCappedAtThirtyTwo() {
        var question = new StringBuilder();
        for (int i = 0; question.length() < HOSTILE; i++) {
            question.append("w").append(i).append(' ');
        }

        assertThat(onSmallStack(question.toString()).text().split(" ")).hasSize(QueryTerms.MAX_TERMS);
    }

    @Test
    void surrogatePairsAndLoneSurrogates() {
        assertThat(onSmallStack("😀".repeat(HOSTILE / 2)).isEmpty()).isTrue();
        assertThat(onSmallStack("\ud800".repeat(HOSTILE) + " notice").text()).isEqualTo("notice");
    }

    private static QueryTerms onSmallStack(String question) {
        var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(null, task, "small-stack", SMALL_STACK_BYTES));
        try {
            return executor.submit(() -> QueryTerms.of(question)).get(Duration.ofSeconds(30).toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("could not read the words of the question", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
