package dev.proppilot.guardrail;

import java.util.ArrayList;
import java.util.List;

/**
 * The tenant names of one reading of the table, folded, in the order of the table. A tenant is known by its full name
 * in each language. A name of a single word is not looked for: it would match any sentence that uses the word.
 */
final class TenantRoster {

    /**
     * A tenant whose name an answer wrote.
     *
     * @param tenant the position of the tenant in the roster
     * @param at where in the answer the name starts
     * @param written the name as the answer wrote it
     * @param normalised the name as it was compared: folded
     */
    record Mention(int tenant, int at, String written, String normalised) {
    }

    private final List<List<String>> namesOfTenant;

    private TenantRoster(List<List<String>> namesOfTenant) {
        this.namesOfTenant = namesOfTenant;
    }

    static TenantRoster of(List<TenantNames.Name> names) {
        var tenants = new ArrayList<List<String>>();
        for (var name : names) {
            var folded = new ArrayList<String>(2);
            for (var written : new String[] {name.english(), name.arabic()}) {
                if (written == null) {
                    continue;
                }
                var full = FoldedText.of(written.strip()).text();
                if (full.indexOf(' ') > 0) {
                    folded.add(full);
                }
            }
            tenants.add(List.copyOf(folded));
        }
        return new TenantRoster(List.copyOf(tenants));
    }

    /** Every name of a tenant that the text writes, with its first place, in the order of the table. */
    List<Mention> mentionedIn(FoldedText answer) {
        var mentions = new ArrayList<Mention>();
        for (int tenant = 0; tenant < namesOfTenant.size(); tenant++) {
            for (var name : namesOfTenant.get(tenant)) {
                int at = FoldedText.indexOfName(answer.text(), name, 0);
                if (at >= 0) {
                    var written = answer.written(at, at + name.length());
                    mentions.add(new Mention(tenant, answer.positionOf(at), written, name));
                }
            }
        }
        return mentions;
    }

    /** Whether the text names the tenant, in either language: the model may well write a name in the other one. */
    boolean isNamedIn(int tenant, FoldedText text) {
        return namesOfTenant.get(tenant).stream()
                .anyMatch(name -> FoldedText.indexOfName(text.text(), name, 0) >= 0);
    }
}
