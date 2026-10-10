package dev.proppilot.actions;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    static final int LATEST = 100;

    private final AuditTrail audit;

    public AuditController(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * Who changed what, newest first. Actors are anonymous names; no address is stored.
     *
     * @param actionId when given, every entry of that one action instead of the latest of all, so the trail of an
     *                 older action is whole however much has happened since
     */
    @GetMapping
    public List<AuditView> entries(@RequestParam(required = false) String actionId) {
        var entries = actionId == null ? audit.latest(LATEST) : audit.of(ActionIds.parse(actionId));
        return entries.stream().map(AuditView::of).toList();
    }
}
