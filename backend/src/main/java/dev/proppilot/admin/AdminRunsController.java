package dev.proppilot.admin;

import dev.proppilot.agent.AgentResult;
import dev.proppilot.chat.ChatRunRepository;
import dev.proppilot.chat.RunIds;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminRunsController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final ChatRunRepository runs;

    public AdminRunsController(ChatRunRepository runs) {
        this.runs = runs;
    }

    /**
     * The latest questions with their cost and latency, newest first. Visitors' question text is never public.
     * To page backwards, pass the {@code createdAt} of the last run of the previous page as {@code before}.
     */
    @GetMapping("/runs")
    @Transactional(readOnly = true)
    public List<AdminRunView> runs(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                                   @RequestParam(required = false) AgentResult.Status status,
                                   @RequestParam(required = false) @Size(max = 64) String model,
                                   @RequestParam(required = false) Boolean guardrailFlagged,
                                   @RequestParam(required = false) Instant before) {
        var matching = RunSpecifications.matching(status, model, guardrailFlagged, before);
        return runs.findBy(matching, query -> query.sortBy(NEWEST_FIRST).limit(limit).all())
                .stream().map(AdminRunView::of).toList();
    }

    /**
     * One run with its trace, its question and what its tools were asked and said; 404 for a run that is unknown and
     * for an id that is not a UUID.
     */
    @GetMapping("/runs/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<AdminRunDetailView> run(@PathVariable String id) {
        return RunIds.parse(id)
                .flatMap(runs::findById)
                .map(run -> ResponseEntity.ok(AdminRunDetailView.of(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
