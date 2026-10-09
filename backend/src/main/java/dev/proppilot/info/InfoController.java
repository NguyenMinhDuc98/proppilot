package dev.proppilot.info;

import dev.proppilot.agent.llm.LlmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/info")
public class InfoController {

    private final LlmClient llm;

    public InfoController(LlmClient llm) {
        this.llm = llm;
    }

    @GetMapping
    public InfoView info() {
        var provider = llm.provider();
        return new InfoView(provider.equals("offline") ? "offline" : "claude", provider, llm.model());
    }
}
