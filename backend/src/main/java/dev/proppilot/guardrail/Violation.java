package dev.proppilot.guardrail;

import dev.proppilot.text.TraceText;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A claim of an answer that nothing the run was given supports. The texts are cut to {@value #MAX_LENGTH} characters
 * and free of control characters, so a violation can be stored, logged and sent to the browser as it is.
 *
 * @param value the claim as the answer wrote it, such as {@code 4,950}
 * @param normalised the claim in the form it was compared in, such as {@code 4950}
 */
public record Violation(ClaimKind kind, String value, String normalised) {

    public static final int MAX_LENGTH = 40;

    public Violation {
        value = TraceText.capped(value, MAX_LENGTH);
        normalised = TraceText.capped(normalised, MAX_LENGTH);
    }

    /** The violations as one log line fragment: {@code NUMBER "4,950", UNIT_CODE "A-204"}. Never the answer itself. */
    public static String describe(List<Violation> violations) {
        return violations.stream().map(v -> v.kind() + " \"" + v.value() + "\"").collect(Collectors.joining(", "));
    }
}
