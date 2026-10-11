package dev.proppilot.guardrail;

import java.util.List;
import java.util.Set;

/**
 * Everything an answer may rest on: what the tools returned, and what the person and the app told the model.
 *
 * @param toolResults the content of every tool result of the run, an error text included, as the model read it
 * @param context the question, the earlier turns of the conversation and any text the app wrote for the turn (a
 *                decision notice, today's date). It is the texts the model was given, never the correction the
 *                guardrail asks for, which would otherwise make every value it lists grounded
 * @param retrievedRefs the refs of the lease clauses the searches of the run returned: the only citations that are not
 *                      made up
 */
public record Evidence(List<String> toolResults, List<String> context, Set<String> retrievedRefs) {

    public Evidence {
        toolResults = List.copyOf(toolResults);
        context = List.copyOf(context);
        retrievedRefs = Set.copyOf(retrievedRefs);
    }
}
