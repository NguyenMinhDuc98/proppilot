package dev.proppilot.actions;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    static final int LATEST = 100;

    private final AuditTrail audit;

    public AuditController(AuditTrail audit) {
        this.audit = audit;
    }

    /** Who changed what, newest first. Actors are anonymous names; no address is stored. */
    @GetMapping
    public List<AuditView> latest() {
        return audit.latest(LATEST).stream().map(AuditView::of).toList();
    }
}
