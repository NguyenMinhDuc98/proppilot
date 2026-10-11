package dev.proppilot.guardrail;

/** What the guardrail concluded about the answer a run delivered. A run it did not check has no status. */
public enum GuardrailStatus {

    /** Checked: nothing in the answer needed grounding that the run could not give. */
    OK,
    /** Some values of the answer could not be grounded, and the answer was delivered as it was. */
    FLAGGED,
    /** The first answer had such values and the answer asked for again has none. */
    RETRIED_OK,
    /** The answer asked for again still has such values. */
    RETRIED_FLAGGED
}
