package dev.proppilot.guardrail;

/** What an answer states that the grounding guardrail can hold against the evidence of its run. */
public enum ClaimKind {

    /** A figure: an amount, a count, a percentage, a year or a day. */
    NUMBER,
    /** A unit code such as A-203. */
    UNIT_CODE,
    /** The full name of a tenant, in English or in Arabic. */
    TENANT_NAME,
    /** A lease clause cited in square brackets, such as [LSE-A-203#5]. */
    CITATION
}
