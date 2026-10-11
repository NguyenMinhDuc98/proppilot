package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises questions about what a lease says (late fee, notice period, renewal, maintenance responsibility, pets,
 * early termination, deposit and the other clauses) and about which leases end soon, in English and Arabic, and turns
 * them into calls of the lease tools.
 *
 * <p>A question that asks about leases that end and also names a lease term is a combined question: the first call
 * lists the leases, and {@link #followUp} turns the units it returned into the search of their documents.
 *
 * <p>Matching runs on a folded copy of the question (see {@link Folding}). The patterns are alternations of words with
 * no repeated group, so a long question costs time in proportion to its length.
 */
final class OfflineLeaseIntents {

    /** The most units one search names, which is what the tool accepts. */
    private static final int MOST_UNITS = 10;
    private static final int MOST_CLAUSES = 8;
    private static final int DEFAULT_CLAUSES = 4;
    private static final int QUERY_LENGTH = 500;
    /** The longest tenant name the tool takes. */
    private static final int MOST_NAME_LENGTH = 100;
    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_END = "(?![\\p{L}\\p{N}])";
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    /** Leases as a list: "which leases ...", not "the lease". */
    private static final Pattern LEASES = Pattern.compile(WORD_START + "(?:leases|contracts|tenancies|rentals"
            + "|(?:which|any|every|what)\\s+(?:lease|contract|tenancy))" + WORD_END + "|عقود|ايجارات|(?:اي|كل)\\s+عقد",
            FLAGS);
    /** Words that say a lease runs out; "end" alone needs a window as well, because a lease also has an end clause. */
    private static final Pattern EXPIRY = Pattern.compile(WORD_START + "(?:expir\\p{L}*|run(?:s|ning)?\\s+out"
            + "|(?:up|due|coming up)\\s+for\\s+renewal|renewals?\\s+(?:due|coming))" + WORD_END
            + "|انتهاء|نته[يا]|نقض[يا]", FLAGS);
    private static final Pattern END = Pattern.compile(WORD_START + "(?:end|ends|ending)" + WORD_END, FLAGS);
    private static final Pattern WINDOW = Pattern.compile(WORD_START + "(?:soon|next|within|coming|upcoming"
            + "|this\\s+(?:month|week|quarter|year)|\\d{1,3}\\s*(?:days?|weeks?|months?))" + WORD_END, FLAGS);
    /** "up for renewal" says a lease ends, not that the question is about the renewal clause. */
    private static final Pattern RENEWAL_DUE = Pattern.compile(WORD_START
            + "(?:(?:up|due|coming up|ready|eligible)\\s+for\\s+renewal|renewals?\\s+(?:due|coming))" + WORD_END,
            FLAGS);
    /** A question about the lease as a whole, with no clause named. */
    private static final Pattern WHOLE_LEASE = Pattern.compile(WORD_START + "(?:clauses?|terms and conditions"
            + "|lease\\s+(?:terms?|conditions?|agreement|document|contract|says?|states?|clauses?))" + WORD_END
            + "|بنود|بند|شروط العقد|شروط الايجار|وثيقه العقد|عقد الايجار|ينص العقد", FLAGS);
    private static final Pattern TENANT_NAME = Pattern.compile(
            "(?i:tenant|resident|lessee)\\s+([A-Z][A-Za-z'-]{1,30}(?:\\s+[A-Z][A-Za-z'-]{1,30}){0,2})");

    private final ObjectMapper json;

    OfflineLeaseIntents(ObjectMapper json) {
        this.json = json;
    }

    /**
     * The first call a question about leases needs; empty when it is not one. A question that combines leases that end
     * with a lease term starts with the list of leases.
     */
    Optional<OfflineIntentRouter.Call> route(String question) {
        var folded = folded(question);
        if (isAboutLeasesThatEnd(folded)) {
            var call = call("find_expiring_leases");
            ExpiryWindow.days(folded).ifPresent(days -> call.args().put("within_days", days));
            return Optional.of(call);
        }
        var topics = topicsIn(folded);
        if (topics.isEmpty() && !WHOLE_LEASE.matcher(folded).find()) {
            return Optional.empty();
        }
        return Optional.of(search(question, folded, topics, unitCodes(folded)));
    }

    /**
     * The call that comes after the leases that end were listed: the search of the lease term the question names, in
     * the documents of the units that were listed. Empty when the question names no lease term or no unit was listed.
     *
     * @param listed the result of {@code find_expiring_leases}
     */
    Optional<OfflineIntentRouter.Call> followUp(String question, JsonNode listed) {
        var folded = folded(question);
        var topics = topicsIn(folded);
        var units = new ArrayList<String>();
        for (var lease : listed.path("leases")) {
            if (units.size() < MOST_UNITS && lease.hasNonNull("unitCode")) {
                units.add(lease.get("unitCode").asText());
            }
        }
        if (topics.isEmpty() || units.isEmpty()) {
            return Optional.empty();
        }
        var call = search(question, folded, topics, units);
        call.args().put("top_k", MOST_CLAUSES);
        return Optional.of(call);
    }

    private static String folded(String question) {
        return Folding.fold(Folding.withoutDiacritics(question));
    }

    private static boolean isAboutLeasesThatEnd(String folded) {
        if (!LEASES.matcher(folded).find()) {
            return false;
        }
        return EXPIRY.matcher(folded).find() || (END.matcher(folded).find() && WINDOW.matcher(folded).find());
    }

    private static List<LeaseTopic> topicsIn(String folded) {
        return LeaseTopic.in(RENEWAL_DUE.matcher(folded).replaceAll(" "));
    }

    private OfflineIntentRouter.Call search(String question, String folded, List<LeaseTopic> topics,
                                            List<String> units) {
        var call = call("search_lease_documents");
        call.put("query", topics.isEmpty() ? cut(question.strip()) : queryOf(topics));
        var named = units.isEmpty() ? unitCodes(folded) : units;
        if (named.size() == 1) {
            call.put("unit_code", named.get(0));
        } else if (named.size() > 1) {
            var codes = call.args().putArray("unit_codes");
            named.forEach(codes::add);
        } else {
            tenantName(question).ifPresent(name -> call.put("tenant_name", name));
        }
        boolean oneLease = named.size() == 1 || (named.isEmpty() && call.args().has("tenant_name"));
        int clauses = Math.min(MOST_CLAUSES, (oneLease ? 2 : DEFAULT_CLAUSES) * Math.max(1, topics.size()));
        if (clauses != DEFAULT_CLAUSES) {
            call.args().put("top_k", clauses);
        }
        return call;
    }

    private static String queryOf(List<LeaseTopic> topics) {
        return String.join(" ", topics.stream().map(LeaseTopic::query).toList());
    }

    /** The unit codes the question names, once each, up to what one search takes. */
    private static List<String> unitCodes(String folded) {
        var codes = new LinkedHashSet<String>();
        var unit = OfflineIntentRouter.UNIT_CODE.matcher(folded);
        while (codes.size() < MOST_UNITS && unit.find()) {
            codes.add(unit.group(1).toUpperCase() + "-" + unit.group(2));
        }
        return new ArrayList<>(codes);
    }

    /** The name after "tenant" in English, or after "المستأجر" in Arabic, without the punctuation that ends it. */
    private static Optional<String> tenantName(String question) {
        var english = TENANT_NAME.matcher(question);
        var name = english.find() ? Optional.of(english.group(1))
                : OfflineIntentRouter.first(OfflineIntentRouter.TENANT_NAME_AR, question);
        return name.map(OfflineLeaseIntents::withoutTrailingPunctuation)
                .filter(text -> !text.isEmpty() && text.length() <= MOST_NAME_LENGTH);
    }

    private static String withoutTrailingPunctuation(String name) {
        int end = name.length();
        while (end > 0 && !Character.isLetterOrDigit(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(0, end);
    }

    /** At most what the tool takes, never between the two halves of a character. */
    private static String cut(String text) {
        if (text.length() <= QUERY_LENGTH) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(QUERY_LENGTH - 1)) ? QUERY_LENGTH - 1 : QUERY_LENGTH;
        return text.substring(0, end).strip();
    }

    private OfflineIntentRouter.Call call(String tool) {
        return new OfflineIntentRouter.Call(tool, json.createObjectNode());
    }
}
