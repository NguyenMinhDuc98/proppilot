package dev.proppilot.info;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import dev.proppilot.rag.FakeEmbeddingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** /api/info with an embedding provider switched on. */
class InfoHybridIT extends FakeEmbeddingIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void searchIsHybridWhenEmbeddingsAreEnabled() throws Exception {
        mvc.perform(get("/api/info")).andExpect(jsonPath("$.retrieval").value("hybrid"));
    }

    @Test
    void searchIsByKeywordsWhenTheyAreNot() throws Exception {
        fake.enabled(false);

        mvc.perform(get("/api/info")).andExpect(jsonPath("$.retrieval").value("keyword"));
    }
}
