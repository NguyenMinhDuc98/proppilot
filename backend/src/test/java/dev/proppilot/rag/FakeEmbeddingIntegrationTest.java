package dev.proppilot.rag;

import dev.proppilot.rag.embed.FakeEmbeddingClient;
import dev.proppilot.rag.embed.FakeEmbeddingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Base for tests that run on the stored lease corpus with embeddings switched on, on a fake provider. The database is
 * shared by the whole test run and other tests expect the chunks to have no embedding, so every test starts and ends
 * with none.
 */
@Import(FakeEmbeddingConfig.class)
public abstract class FakeEmbeddingIntegrationTest extends CorpusIntegrationTest {

    @Autowired
    protected FakeEmbeddingClient fake;

    @BeforeEach
    void startWithoutEmbeddings() {
        fake.reset();
        removeEmbeddings();
    }

    @AfterEach
    void leaveNoEmbeddings() {
        removeEmbeddings();
    }

    private void removeEmbeddings() {
        jdbc.update("update lease_chunks set embedding = null, embedding_model = null");
    }
}
