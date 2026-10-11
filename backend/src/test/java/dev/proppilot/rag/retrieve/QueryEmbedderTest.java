package dev.proppilot.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.rag.embed.FakeEmbeddingClient;
import org.junit.jupiter.api.Test;

class QueryEmbedderTest {

    private final FakeEmbeddingClient fake = new FakeEmbeddingClient();
    private final QueryEmbedder embedder = new QueryEmbedder(fake);

    @Test
    void aQuestionAskedAgainIsAnsweredFromTheCache() {
        var first = embedder.embed("how long is the notice period?");
        var second = embedder.embed("how long is the notice period?");

        assertThat(fake.queryCalls).hasSize(1);
        assertThat(first).isPresent();
        assertThat(second.orElseThrow()).containsExactly(first.orElseThrow());
    }

    @Test
    void anotherQuestionIsEmbeddedOnItsOwn() {
        embedder.embed("notice period");
        embedder.embed("late fee");

        assertThat(fake.queryCalls).containsExactly("notice period", "late fee");
    }

    @Test
    void theCacheHoldsTheMostRecentlyUsedTwoHundredAndFiftySixQuestions() {
        embedder.embed("question 0");
        for (int i = 1; i < QueryEmbedder.CAPACITY; i++) {
            embedder.embed("question " + i);
        }
        embedder.embed("question 0");
        embedder.embed("one more");
        fake.queryCalls.clear();

        embedder.embed("question 0");
        embedder.embed("question 1");

        assertThat(fake.queryCalls).as("0 was used again and stays, 1 was the least recently used")
                .containsExactly("question 1");
    }

    @Test
    void nothingIsEmbeddedWhenEmbeddingsAreOff() {
        fake.enabled(false);

        assertThat(embedder.embed("notice period")).isEmpty();
        assertThat(fake.queryCalls).isEmpty();
    }

    @Test
    void aFailureIsNotThrownAndNotCached() {
        fake.failQueriesWith(LlmException.retryable("Voyage API returned 503", ErrorCode.LLM_OVERLOADED));

        assertThat(embedder.embed("notice period")).isEmpty();
        fake.failQueriesWith(null);
        assertThat(embedder.embed("notice period")).isPresent();

        assertThat(fake.queryCalls).hasSize(2);
    }

    @Test
    void anyFailureIsAbsorbedNotOnlyTheProvidersOwn() {
        fake.failQueriesWith(new IllegalStateException("boom"));

        assertThat(embedder.embed("notice period")).isEmpty();
    }
}
