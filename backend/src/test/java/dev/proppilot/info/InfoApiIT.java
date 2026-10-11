package dev.proppilot.info;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.rag.CorpusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** /api/info as the application runs with its default settings: the offline assistant and no embeddings. */
class InfoApiIT extends CorpusIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void keepsTheFieldsTheWebAppAlreadyReads() throws Exception {
        mvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("offline"))
                .andExpect(jsonPath("$.provider").value("offline"))
                .andExpect(jsonPath("$.model").value("rule-based"))
                .andExpect(jsonPath("$.maxHistoryItems").value(20));
    }

    @Test
    void searchIsByKeywordsAndTheLeaseDocumentsAreCounted() throws Exception {
        mvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieval").value("keyword"))
                .andExpect(jsonPath("$.leaseDocuments").value(30));
    }
}
