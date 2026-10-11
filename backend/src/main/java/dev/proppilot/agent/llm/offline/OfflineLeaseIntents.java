package dev.proppilot.agent.llm.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * <p>Matching runs on a folded copy of the question (see {@link Folding}). The patterns are alternations of words, and
 * what they repeat is a single character or a bounded gap, with a run of white space taken in one way only, so a long
 * question costs time in proportion to its length.
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
    /** The units or tenants of a list: "which units have a lease that ends", not "the lease of unit A-203". */
    private static final Pattern LISTED = Pattern.compile(WORD_START + "(?:which|what|any|every|all|list|show)"
            + "\\s+(?:of\\s+)?(?:the\\s+)?(?:units?|tenants?|residents?|apartments?|properties|buildings?)(?:'s)?"
            + WORD_END + "|(?<![\\p{L}\\p{N}])(?:ال)?(?:وحدات|مستاجرين|مستاجرون|شقق|عقارات|مباني)", FLAGS);
    private static final Pattern A_LEASE = Pattern.compile(WORD_START + "(?:lease|contract|tenancy)" + WORD_END
            + "|عقد", FLAGS);
    /** "Up for renewal" and "needs renewal" say a lease ends, not that the question is about the renewal clause. */
    private static final String RENEWAL_DUE_WORDS = "(?:up|due|coming up|ready|eligible)\\s+for\\s+renewal"
            + "|renewals?\\s+(?:due|coming)"
            + "|(?:needs?|requires?)\\s+(?:to\\s+be\\s+renewed"
            + "|renewal(?!\\s+(?:notice|terms?|clauses?|fees?|conditions?)))";
    private static final Pattern RENEWAL_DUE = Pattern.compile(WORD_START + "(?:" + RENEWAL_DUE_WORDS + ")" + WORD_END,
            FLAGS);
    /** Words that say a lease runs out; "end" alone needs a window as well, because a lease also has an end clause. */
    private static final Pattern EXPIRY = Pattern.compile(WORD_START + "(?:expir\\p{L}*|run(?:s|ning)?\\s+out"
            + "|" + RENEWAL_DUE_WORDS + ")" + WORD_END + "|انتهاء|نته[يا]|نقض[يا]", FLAGS);
    private static final Pattern END = Pattern.compile(WORD_START + "(?:end|ends|ending)" + WORD_END, FLAGS);
    /** Words that look ahead. A number of days is not one: a lease also has a notice period and a length in months. */
    private static final Pattern WINDOW = Pattern.compile(WORD_START + "(?:soon|next|within|coming|upcoming"
            + "|this\\s+(?:month|week|quarter|year))" + WORD_END, FLAGS);
    private static final String COUNT = "(?:\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven"
            + "|twelve)";
    /** How long a notice is: "90 days notice", "notice of 3 months", "notice within 30 days"; a term, not a window. */
    private static final Pattern NOTICE_LENGTH = Pattern.compile(WORD_START + "(?:" + COUNT
            + "[- ]*(?:days?|weeks?|months?)(?:'s?)?\\s+(?:written\\s+)?(?:notice|warning)"
            + "|notice(?:\\s+period)?\\s+(?:of|is|within|in)\\s+(?:at\\s+least\\s+)?" + COUNT
            + "[- ]*(?:days?|weeks?|months?))" + WORD_END
            + "|(?:اشعار|اخطار)\\p{L}*\\s+(?:\\p{L}+\\s+)?\\d{1,3}\\s*(?:يوما|يوم|ايام|اسبوعا|اسبوع|اسابيع|شهرا|شهر|اشهر)"
            + "|\\d{1,3}\\s*(?:يوما|يوم|ايام|اسبوعا|اسبوع|اسابيع|شهرا|شهر|اشهر)\\s+(?:اشعار|اخطار)", FLAGS);
    /** A question about the lease as a whole, with no clause named. */
    private static final Pattern WHOLE_LEASE = Pattern.compile(WORD_START + "(?:clauses?|terms and conditions"
            + "|lease\\s+(?:terms?|conditions?|agreement|document|contract|says?|states?|clauses?))" + WORD_END
            + "|بنود|بند|شروط العقد|شروط الايجار|وثيقه العقد|عقد الايجار|ينص العقد", FLAGS);

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
        var ending = leasesThatEnd(folded);
        if (ending.isPresent()) {
            return ending;
        }
        var topics = topicsIn(folded);
        if (topics.isEmpty() && !WHOLE_LEASE.matcher(folded).find()) {
            return Optional.empty();
        }
        return Optional.of(search(question, topics, unitCodes(folded)));
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
        var call = search(question, topics, units);
        call.args().put("top_k", MOST_CLAUSES);
        return Optional.of(call);
    }

    private static String folded(String question) {
        return Folding.fold(Folding.withoutDiacritics(question));
    }

    /** The list of leases that end, when the question asks for one; the window is the one it names, if any. */
    private Optional<OfflineIntentRouter.Call> leasesThatEnd(String folded) {
        if (!isAboutSeveralLeases(folded)) {
            return Optional.empty();
        }
        // A number of days that is the length of a notice is a lease term, so it is no window and no sign of an end.
        var withoutNotice = NOTICE_LENGTH.matcher(folded).replaceAll(" ");
        var days = ExpiryWindow.days(withoutNotice);
        boolean looksAhead = days.isPresent() || WINDOW.matcher(withoutNotice).find();
        boolean noticeLengthGiven = !withoutNotice.equals(folded);
        boolean ends = EXPIRY.matcher(folded).find() ? looksAhead || !noticeLengthGiven
                : END.matcher(folded).find() && looksAhead;
        if (!ends) {
            return Optional.empty();
        }
        var call = call("find_expiring_leases");
        days.ifPresent(window -> call.args().put("within_days", window));
        return Optional.of(call);
    }

    /**
     * "Which leases", or "which units have a lease": a list. A question about the lease of a unit it names is not one.
     */
    private static boolean isAboutSeveralLeases(String folded) {
        if (LEASES.matcher(folded).find()) {
            return true;
        }
        return LISTED.matcher(folded).find() && A_LEASE.matcher(folded).find()
                && !OfflineIntentRouter.UNIT_CODE.matcher(folded).find();
    }

    private static List<LeaseTopic> topicsIn(String folded) {
        return LeaseTopic.in(RENEWAL_DUE.matcher(folded).replaceAll(" "));
    }

    /**
     * @param units the units to search; none searches the tenant the question names, or every document
     */
    private OfflineIntentRouter.Call search(String question, List<LeaseTopic> topics, List<String> units) {
        var call = call("search_lease_documents");
        call.put("query", topics.isEmpty() ? cut(question.strip()) : queryOf(topics));
        if (units.size() == 1) {
            call.put("unit_code", units.get(0));
        } else if (units.size() > 1) {
            var codes = call.args().putArray("unit_codes");
            units.forEach(codes::add);
        } else {
            tenantName(question).ifPresent(name -> call.put("tenant_name", name));
        }
        boolean oneLease = units.size() == 1 || call.args().has("tenant_name");
        // Room for a clause of each named lease, which the best clauses of a few leases would otherwise crowd out.
        int perTopic = oneLease ? 2 : Math.max(DEFAULT_CLAUSES, units.size());
        int clauses = Math.min(MOST_CLAUSES, perTopic * Math.max(1, topics.size()));
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
            codes.add(unit.group(1).toUpperCase(Locale.ROOT) + "-" + unit.group(2));
        }
        return new ArrayList<>(codes);
    }

    /** The name of the tenant the question is about, when it gives one that the tool can take. */
    private static Optional<String> tenantName(String question) {
        return TenantName.in(question).filter(name -> name.length() <= MOST_NAME_LENGTH);
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
