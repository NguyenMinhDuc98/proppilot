package dev.proppilot.guardrail;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The grounding guardrail as the agent loop uses it: the mode it runs in, and a check that can never fail a question.
 * The check costs no model call; what the mode does with its findings is the loop's to decide.
 */
public final class Guardrail {

    private static final Logger log = LoggerFactory.getLogger(Guardrail.class);

    private final GuardrailMode mode;
    private final GroundingChecker checker;

    public Guardrail(GuardrailMode mode, GroundingChecker checker) {
        this.mode = mode;
        this.checker = checker;
    }

    public GuardrailMode mode() {
        return mode;
    }

    /**
     * @return the claims of the answer that could not be grounded, none when it is grounded; empty when nothing was
     *         checked: the mode is {@code off}, or the check itself failed, which leaves the answer as it was
     */
    public Optional<List<Violation>> check(String answer, Evidence evidence) {
        if (mode == GuardrailMode.OFF) {
            return Optional.empty();
        }
        try {
            return Optional.of(checker.check(answer, evidence));
        } catch (RuntimeException e) {
            // The class only: the message of an exception can quote the answer.
            log.warn("An answer could not be checked for grounding ({})", e.getClass().getName());
            return Optional.empty();
        }
    }
}
