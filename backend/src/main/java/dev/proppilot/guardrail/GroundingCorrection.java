package dev.proppilot.guardrail;

import java.util.List;

/**
 * The turn the app writes to ask for an answer again, once, when the first one states values that nothing supports.
 * The values in it are the figures, codes, names and refs that the checker read out of the model's own answer, cut to
 * {@value Violation#MAX_LENGTH} characters: no text of a person's.
 */
public final class GroundingCorrection {

    public static final String MARKER = "[GROUNDING CHECK]";

    private GroundingCorrection() {
    }

    public static String message(List<Violation> violations) {
        return MARKER + " This message is written by the app, not by the user. Your answer states values that are not "
                + "in the data you were given in this conversation: " + Violation.describe(violations) + ".\n\n"
                + "Check the tool results and the other data of this conversation, and answer the question again "
                + "using only values that you find in them. If a figure is not available there, say so plainly "
                + "instead of estimating it. Cite a lease clause only with a ref that a search result of this "
                + "conversation returned. Write the answer in the language of your previous answer, and do not "
                + "mention this check.";
    }
}
