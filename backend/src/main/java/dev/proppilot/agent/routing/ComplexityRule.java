package dev.proppilot.agent.routing;

/** One reason a question is more work than a lookup, and the points it adds to the score of the question. */
public enum ComplexityRule {

    /**
     * The question reaches into two or more data areas: payments, occupancy, lease clauses, expiring leases, drafted
     * messages and changes to make.
     */
    SEVERAL_DATA_AREAS(2),
    /** A time window and a lease term in one question, which takes the leases that end and then their clauses. */
    WINDOW_WITH_LEASE_TERM(2),
    /** The question runs to more than {@value ComplexityAssessor#LONG_QUESTION_LENGTH} characters. */
    LONG_QUESTION(1),
    /** A word that asks for steps or an aggregate: "and then", "for each", "compare", "both", "summarise ... and". */
    SEQUENCING_WORD(1),
    /** The question names {@value ComplexityAssessor#SEVERAL_UNITS} or more units. */
    MANY_UNITS(1),
    /** The conversation is already {@value ComplexityAssessor#LONG_CONVERSATION} messages long or more. */
    LONG_CONVERSATION(1);

    private final int points;

    ComplexityRule(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
