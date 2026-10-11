package dev.proppilot.guardrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Finds what an answer states that the run it came from cannot support. It does not judge whether the answer is right:
 * a figure, a unit code, a tenant name or a citation is <em>grounded</em> when the same one is in the evidence, and
 * anything else is reported. The claims are the ones a person acts on:
 *
 * <ul>
 *   <li>{@link ClaimKind#NUMBER}: every figure must be one of the figures of the evidence, which are those in the tool
 *       results (in their strings too: a date, a unit code), the lengths of their arrays, and those in the question,
 *       the conversation and the text the app wrote. {@code 4,950}, {@code 4950.00} and {@code ٤٩٥٠} are one figure; a
 *       rounded one is another. A whole number up to {@value #SMALL_NUMBER} is never reported (a list is numbered, a
 *       question asks for the top 3), and neither is the number of an item of a list. The digits of a unit code or of
 *       a citation are not figures of their own</li>
 *   <li>{@link ClaimKind#UNIT_CODE}: every code such as A-203 must be in the evidence</li>
 *   <li>{@link ClaimKind#TENANT_NAME}: a tenant that the answer names must be named in the evidence, in either
 *       language</li>
 *   <li>{@link ClaimKind#CITATION}: every {@code [LSE-...]} marker must be a ref that a lease search of the run
 *       returned. A marker in the text of a clause is quoted text and makes nothing real</li>
 * </ul>
 *
 * <p>Each distinct claim is reported once, in the order the answer states them, up to {@value #MAX_VIOLATIONS}. The
 * check reads texts by hand-written scans (see {@link ClaimScanner}), so the work is proportional to their length.
 */
public final class GroundingChecker {

    static final int MAX_VIOLATIONS = 10;
    static final int SMALL_NUMBER = 10;

    private final TenantNames tenants;

    public GroundingChecker(TenantNames tenants) {
        this.tenants = tenants;
    }

    /** The claims of {@code answer} that {@code evidence} does not support; empty when it is grounded. */
    public List<Violation> check(String answer, Evidence evidence) {
        var known = KnownValues.of(evidence);
        var found = new ArrayList<Claim>();
        ClaimScanner.ofAnswer(answer, (kind, written, normalised, at) -> {
            if (!isGrounded(known, kind, normalised)) {
                found.add(new Claim(at, new Violation(kind, written, normalised)));
            }
        });
        found.addAll(unverifiedTenants(answer, known));
        found.sort(Comparator.comparingInt(Claim::at));
        return distinct(found);
    }

    private static boolean isGrounded(KnownValues known, ClaimKind kind, String normalised) {
        return switch (kind) {
            case NUMBER -> isSmallWholeNumber(normalised) || known.hasFigure(normalised);
            case UNIT_CODE -> known.hasUnitCode(normalised);
            // A citation; tenant names are not found by the scan but looked for in the text.
            default -> known.hasRef(normalised);
        };
    }

    private static boolean isSmallWholeNumber(String normalised) {
        return normalised.length() <= 2 && normalised.chars().allMatch(Character::isDigit)
                && Integer.parseInt(normalised) <= SMALL_NUMBER;
    }

    private List<Claim> unverifiedTenants(String answer, KnownValues known) {
        var roster = tenants.roster();
        var written = FoldedText.of(answer);
        var unverified = new ArrayList<Claim>();
        for (var mention : roster.mentionedIn(written)) {
            if (!roster.isNamedIn(mention.tenant(), known.text())) {
                unverified.add(new Claim(mention.at(),
                        new Violation(ClaimKind.TENANT_NAME, mention.written(), mention.normalised())));
            }
        }
        return unverified;
    }

    private static List<Violation> distinct(List<Claim> claims) {
        var distinct = new LinkedHashMap<String, Violation>();
        for (var claim : claims) {
            var violation = claim.violation();
            distinct.putIfAbsent(violation.kind() + ":" + violation.normalised(), violation);
            if (distinct.size() == MAX_VIOLATIONS) {
                break;
            }
        }
        return List.copyOf(distinct.values());
    }

    private record Claim(int at, Violation violation) {
    }
}
