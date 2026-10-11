package dev.proppilot.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How a run was answered. Public, like the stream that announces the run's id: the id is a random UUID that only the
 * visitor who asked has seen, and the trace holds neither the question nor the answer.
 */
@RestController
@RequestMapping("/api/runs")
public class RunTraceController {

    private final ChatRunRepository runs;

    public RunTraceController(ChatRunRepository runs) {
        this.runs = runs;
    }

    /** 404 for a run that is unknown and for an id that is not a UUID. */
    @GetMapping("/{id}/trace")
    @Transactional(readOnly = true)
    public ResponseEntity<RunTraceView> trace(@PathVariable String id) {
        return RunIds.parse(id)
                .flatMap(runs::findById)
                .map(run -> ResponseEntity.ok(RunTraceView.of(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
