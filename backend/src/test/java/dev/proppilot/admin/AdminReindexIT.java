package dev.proppilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.llm.LlmException;
import dev.proppilot.rag.FakeEmbeddingIntegrationTest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"proppilot.rag.seed-on-startup=false", "proppilot.admin.token=" + AdminReindexIT.TOKEN})
class AdminReindexIT extends FakeEmbeddingIntegrationTest {

    static final String TOKEN = "integration-test-admin-token-1234";
    private static final String REINDEX = "/api/admin/rag/reindex";

    @Autowired
    MockMvc mvc;

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void refusesARequestWithoutTheRightToken() throws Exception {
        mvc.perform(post(REINDEX)).andExpect(status().isForbidden());
        mvc.perform(post(REINDEX).header("X-Admin-Token", "wrong-token-wrong-token-wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post(REINDEX).header("X-Admin-Token", "")).andExpect(status().isForbidden());
        mvc.perform(post(REINDEX).queryParam("X-Admin-Token", TOKEN)).andExpect(status().isForbidden());

        assertThat(fake.documentCalls).as("nothing was embedded for a caller that was refused").isEmpty();
        assertThat(count("select count(*) from lease_chunks where embedding is not null")).isZero();
    }

    @Test
    void embedsTheChunksAndReportsTheCounts() throws Exception {
        int documents = count("select count(*) from lease_documents");
        int chunks = count("select count(*) from lease_chunks");

        mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(documents))
                .andExpect(jsonPath("$.chunks").value(chunks))
                .andExpect(jsonPath("$.embedded").value(chunks))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(documents).isEqualTo(30);
        assertThat(count("select count(*) from lease_chunks where embedding is null")).isZero();
    }

    @Test
    void aSecondRunHasNothingToEmbed() throws Exception {
        mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
        int calls = fake.documentCalls.size();

        mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(30))
                .andExpect(jsonPath("$.embedded").value(0))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(fake.documentCalls).hasSize(calls);
    }

    @Test
    void writesAMissingDocumentAndEmbedsItsChunks() throws Exception {
        mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
        long leaseId = jdbc.queryForObject("select min(lease_id) from lease_documents", Long.class);
        int chunksOfDocument = count("""
                select count(*) from lease_chunks c join lease_documents d on d.id = c.document_id
                where d.lease_id = %d""".formatted(leaseId));
        jdbc.update("delete from lease_documents where lease_id = ?", leaseId);

        mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(30))
                .andExpect(jsonPath("$.embedded").value(chunksOfDocument))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void aProviderThatFailsIsReportedAndNotAnError() throws Exception {
        int chunks = count("select count(*) from lease_chunks");
        fake.beforeDocumentCall(call -> {
            throw LlmException.retryable("Voyage API returned 429", ErrorCode.LLM_OVERLOADED);
        });

        mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(30))
                .andExpect(jsonPath("$.chunks").value(chunks))
                .andExpect(jsonPath("$.embedded").value(0))
                .andExpect(jsonPath("$.failed").value(chunks));
    }

    @Test
    void aCallWhileARunIsGoingAnswersConflict() throws Exception {
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fake.beforeDocumentCall(call -> {
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var running = CompletableFuture.runAsync(() -> {
            try {
                mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        try {
            assertThat(inside.await(60, TimeUnit.SECONDS)).isTrue();

            mvc.perform(post(REINDEX).header("X-Admin-Token", TOKEN)).andExpect(status().isConflict());
        } finally {
            release.countDown();
            running.get(120, TimeUnit.SECONDS);
        }
    }

    @Test
    void theEndpointAnswersToPostOnly() throws Exception {
        mvc.perform(get(REINDEX).header("X-Admin-Token", TOKEN)).andExpect(status().isMethodNotAllowed());
    }
}
