package dev.proppilot.info;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.config.AdminProperties;
import dev.proppilot.config.ChatProperties;
import dev.proppilot.rag.CorpusCounts;
import dev.proppilot.rag.LeaseDocumentStore;
import dev.proppilot.rag.embed.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InfoControllerTest {

    private MockMvc mvcFor(ChatProperties chat, String provider, String model, boolean embeddings, int documents,
                           String adminToken) {
        var llm = mock(LlmClient.class);
        when(llm.provider()).thenReturn(provider);
        when(llm.model()).thenReturn(model);
        var embedding = mock(EmbeddingClient.class);
        when(embedding.enabled()).thenReturn(embeddings);
        var store = mock(LeaseDocumentStore.class);
        when(store.counts()).thenReturn(new CorpusCounts(documents, documents * 14));
        return MockMvcBuilders.standaloneSetup(
                new InfoController(llm, chat, embedding, store, new AdminProperties(adminToken))).build();
    }

    private MockMvc mvcFor(String provider, String model, int maxHistoryItems, boolean embeddings, int documents) {
        return mvcFor(new ChatProperties(500, maxHistoryItems, 4000, 10, 500), provider, model, embeddings, documents, "");
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
                         "maxHistoryTurnLength":4000,"retrieval":"keyword","leaseDocuments":0,"demoReset":false}""", true));
    }

    @Test
    void reportsClaudeWithTheConfiguredModelAndNothingElse() throws Exception {
        mvcFor("anthropic", "claude-haiku-4-5-20251001", 20).perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"mode":"claude","provider":"anthropic","model":"claude-haiku-4-5-20251001",
                         "maxHistoryItems":20,"maxHistoryTurnLength":4000,"retrieval":"keyword","leaseDocuments":0,"demoReset":false}""",
                        true));
    }

    @Test
    void reportsTheHistoryLimitTheChatEndpointEnforces() throws Exception {
        mvcFor("offline", "rule-based", 10).perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHistoryItems").value(10));
    }

    @Test
    void reportsTheLongestHistoryTurnTheChatEndpointEnforces() throws Exception {
        var chat = new ChatProperties(500, 20, 1500, 10, 500);

        mvcFor(chat, "offline", "rule-based", false, 0, "").perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHistoryTurnLength").value(1500));
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

    @Test
    void saysADemoCanBeResetOnlyWhenTheAdminEndpointsExist() throws Exception {
        var chat = new ChatProperties(500, 20, 4000, 10, 500);

        mvcFor(chat, "anthropic", "claude-haiku-4-5-20251001", false, 0, "a-token-of-at-least-24-characters")
                .perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoReset").value(true));
        mvcFor(chat, "anthropic", "claude-haiku-4-5-20251001", false, 0, "")
                .perform(get("/api/info"))
                .andExpect(jsonPath("$.demoReset").value(false));
    }
}
