package dev.proppilot.info;

import dev.proppilot.agent.llm.LlmClient;
import dev.proppilot.config.AdminProperties;
import dev.proppilot.config.ChatProperties;
import dev.proppilot.rag.LeaseDocumentStore;
import dev.proppilot.rag.embed.EmbeddingClient;
import dev.proppilot.rag.retrieve.RetrievalMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/info")
public class InfoController {

    private final LlmClient llm;
    private final ChatProperties chat;
    private final EmbeddingClient embeddings;
    private final LeaseDocumentStore documents;
    private final AdminProperties admin;

    public InfoController(LlmClient llm, ChatProperties chat, EmbeddingClient embeddings,
                          LeaseDocumentStore documents, AdminProperties admin) {
        this.llm = llm;
        this.chat = chat;
        this.embeddings = embeddings;
        this.documents = documents;
        this.admin = admin;
    }

    @GetMapping
    public InfoView info() {
        var provider = llm.provider();
        return new InfoView(provider.equals("offline") ? "offline" : "claude", provider, llm.model(),
                chat.maxHistoryItems(), chat.maxHistoryTurnLength(), RetrievalMode.of(embeddings.enabled()),
                documents.counts().documents(), admin.enabled());
    }
}
