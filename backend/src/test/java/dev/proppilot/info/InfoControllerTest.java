package dev.proppilot.info;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.config.ChatProperties;
import dev.proppilot.rag.CorpusCounts;
import dev.proppilot.rag.LeaseDocumentStore;
import dev.proppilot.rag.embed.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InfoControllerTest {

    private MockMvc mvcFor(String provider, String model, int maxHistoryItems, boolean embeddings, int documents) {
        var llm = mock(LlmClient.class);
        when(llm.provider()).thenReturn(provider);
        when(llm.model()).thenReturn(model);
        var chat = new ChatProperties(500, maxHistoryItems, 10, 500);
        var embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(embeddings);
        var store = mock(LeaseDocumentStore.class);
        when(store.counts()).thenReturn(new CorpusCounts(documents, documents * 14));
        return MockMvcBuilders.standaloneSetup(new InfoController(llm, chat, embedding, store)).build();
    }

    private MockMvc mvcFor(String provider, String model, int maxHistoryItems) {
        return mvcFor(provider, model, maxHistoryItems, false, 0);
    }

    @Test
    void reportsTheOfflineAssistant() throws Exception {
        mvcFor("offline", "rule-based", 20).perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"mode":"offline","provider":"offline","model":"rule-based","maxHistoryItems":20,
                         "retrieval":"keyword","leaseDocuments":0}""", true));
    }

    @Test
    void reportsClaudeWithTheConfiguredModelAndNothingElse() throws Exception {
        mvcFor("anthropic", "claude-haiku-4-5-20251001", 20).perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"mode":"claude","provider":"anthropic","model":"claude-haiku-4-5-20251001","maxHistoryItems":20,
                         "retrieval":"keyword","leaseDocuments":0}""", true));
    }

    @Test
    void reportsTheHistoryLimitTheChatEndpointEnforces() throws Exception {
        mvcFor("offline", "rule-based", 10).perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHistoryItems").value(10));
    }

    @Test
    void searchIsHybridWhenEmbeddingsAreEnabledAndKeywordOtherwise() throws Exception {
        mvcFor("offline", "rule-based", 20, true, 30).perform(get("/api/info"))
                .andExpect(jsonPath("$.retrieval").value("hybrid"));
        mvcFor("offline", "rule-based", 20, false, 30).perform(get("/api/info"))
                .andExpect(jsonPath("$.retrieval").value("keyword"));
    }

    @Test
    void countsTheStoredLeaseDocuments() throws Exception {
        mvcFor("offline", "rule-based", 20, false, 30).perform(get("/api/info"))
                .andExpect(jsonPath("$.leaseDocuments").value(30));
    }
}
