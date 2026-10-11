package dev.proppilot.admin;

import dev.proppilot.rag.CorpusIndexer;
import dev.proppilot.rag.ReindexResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/rag")
public class AdminRagController {

    private final CorpusIndexer indexer;

    public AdminRagController(CorpusIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Writes the lease documents that are missing or outdated and embeds the chunks that need it, and answers when it
     * is done. Guarded like every admin endpoint. A second call while a run is going answers 409.
     */
    @PostMapping("/reindex")
    public ReindexResult reindex() {
        return indexer.reindex().orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "The lease corpus is already being indexed"));
    }
}
