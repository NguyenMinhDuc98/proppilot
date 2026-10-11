package dev.proppilot.guardrail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every figure, unit code and tenant name that the evidence of a run states, for an answer to be held against. Built
 * once for each check.
 */
final class KnownValues {

    /** Cannot be part of a name or a figure, so two texts that are joined never make a name between them. */
    private static final char SEPARATOR = '\u0001';

    private final Set<String> figures = new HashSet<>();
    private final Set<String> unitCodes = new HashSet<>();
    private final Set<String> refs;
    private final Evidence evidence;
    private FoldedText folded;

    private KnownValues(Evidence evidence) {
        this.refs = evidence.retrievedRefs();
        this.evidence = evidence;
        for (var text : evidence.context()) {
            ClaimScanner.ofEvidence(text, this::learn);
        }
        for (var result : evidence.toolResults()) {
            ToolResultReader.read(result, this::learn, length -> figures.add(Integer.toString(length)));
        }
    }

    static KnownValues of(Evidence evidence) {
        return new KnownValues(evidence);
    }

    private void learn(ClaimKind kind, String written, String normalised, List<String> separately, int at) {
        if (kind == ClaimKind.NUMBER) {
            figures.add(normalised);
        } else {
            unitCodes.add(normalised);
        }
    }

    boolean hasFigure(String normalised) {
        return figures.contains(normalised);
    }

    boolean hasUnitCode(String normalised) {
        return unitCodes.contains(normalised);
    }

    /** Whether a search of the run returned this ref. */
    boolean hasRef(String ref) {
        return refs.contains(ref);
    }

    /** The evidence as one folded text, for finding names in it; only built when an answer names a tenant. */
    FoldedText text() {
        if (folded == null) {
            var texts = new ArrayList<String>(evidence.context());
            texts.addAll(evidence.toolResults());
            folded = FoldedText.of(String.join(String.valueOf(SEPARATOR), texts));
        }
        return folded;
    }
}
