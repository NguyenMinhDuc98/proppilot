package dev.proppilot.admin;

import dev.proppilot.chat.ChatRunRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminRunsController {

    private final ChatRunRepository runs;

    public AdminRunsController(ChatRunRepository runs) {
        this.runs = runs;
    }

    /** The latest questions with their cost and latency, newest first. Visitors' question text is never public. */
    @GetMapping("/runs")
    @Transactional(readOnly = true)
    public List<AdminRunView> runs(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return runs.findAllByOrderByCreatedAtDesc(Limit.of(limit)).stream().map(AdminRunView::of).toList();
    }
}
