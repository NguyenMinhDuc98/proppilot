package dev.proppilot.guardrail;

import java.util.Locale;

/** What the grounding guardrail does with an answer. */
public enum GuardrailMode {

    /** Nothing is checked. */
    OFF,
    /** The answer is checked and any value that cannot be grounded is flagged. Costs no model call. */
    WARN,
    /** As {@link #WARN}, and an answer with such values is asked for once more before it is flagged. */
    RETRY;

    /** A blank setting is {@link #WARN}, so a variable that a host passes on empty cannot stop the backend. */
    public static GuardrailMode parse(String setting) {
        if (setting == null || setting.isBlank()) {
            return WARN;
        }
        return switch (setting.strip().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "warn" -> WARN;
            case "retry" -> RETRY;
            default -> throw new IllegalArgumentException("GUARDRAIL_MODE must be off, warn or retry");
        };
    }
}
