package dev.proppilot.admin;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminResetController {

    private final DemoResetService demoReset;

    public AdminResetController(DemoResetService demoReset) {
        this.demoReset = demoReset;
    }

    /** Called by the nightly job of the public demo. Guarded like every admin endpoint. */
    @PostMapping("/reset")
    public ResetView reset() {
        return demoReset.reset();
    }
}
