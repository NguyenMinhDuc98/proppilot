package dev.proppilot.agent.llm.offline;

import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the name of the tenant a question about a lease is about, when it names one. Two things count as a name:
 * the words after "tenant", "resident" or "lessee" (in Arabic "المستأجر"), in any case, as in "for tenant ahmed
 * al-harbi"; and, with no such word, a run of capitalised words that is not a place, a building or a lease term, as in
 * "late fee for Sara Al-Qahtani" and "in Sara Al-Qahtani's lease". A possessive is taken off the name.
 *
 * <p>The name is only what the question says, up to three words. It is not checked against the tenants, and a word
 * after "tenant" that is a plain word of the question ("the tenant must give notice") is not taken for one. The text is
 * read as a list of words, so the time it takes is in proportion to the question.
 */
final class TenantName {

    private static final int MOST_WORDS = 3;
    private static final int LONGEST_WORD = 30;

    private static final Set<String> TENANT_WORDS = Set.of("tenant", "resident", "lessee");
    /** A word that makes "tenant" a role, so that the word after it is not the name: "the tenant must give notice". */
    private static final Set<String> DETERMINERS = Set.of("the", "a", "an", "each", "every", "this", "that", "my",
            "our", "their", "his", "her", "your", "any", "which", "what", "whose", "per", "no", "one", "all", "some");
    private static final Set<String> FILLERS = Set.of("named", "called", "name", "is", "was");
    /** The prepositions that follow a name: "tenant sara in the lease". */
    private static final Set<String> PREPOSITIONS = Set.of("in", "on", "at", "under", "for", "of", "with", "and");
    /** Words of a question that follow "tenant" and are not a name, in English. */
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of("in", "on", "at", "under", "of", "for", "to", "by",
            "with", "from", "and", "or", "if", "when", "where", "who", "whose", "whom", "that", "which", "are", "be",
            "been", "has", "have", "had", "do", "does", "did", "can", "could", "may", "must", "should", "will",
            "would", "shall", "pay", "pays", "paid", "owe", "owes", "live", "lives", "living", "rent", "rents",
            "give", "gives", "leave", "leaves", "move", "moves", "keep", "keeps", "want", "wants", "need", "needs",
            "get", "gets", "receive", "receives", "sign", "signs", "make", "makes", "take", "takes", "provide",
            "provides", "send", "sends", "return", "returns", "remove", "removes", "report", "reports", "use",
            "uses", "park", "parks", "smoke", "smokes", "change", "changes", "renew", "renews", "end", "ends",
            "stay", "stays", "sublet", "sublets", "terminate", "terminates", "cancel", "cancels", "break", "breaks",
            "not", "unit", "lease", "contract", "agreement", "payment", "notice");
    /** Words that open a sentence or a question, and so are capitalised though they are no name. */
    private static final Set<String> OPENERS = Set.of("what", "which", "who", "whom", "whose", "how", "when", "where",
            "why", "is", "are", "was", "were", "does", "do", "did", "can", "could", "will", "would", "should", "may",
            "must", "show", "tell", "give", "list", "find", "compare", "check", "explain", "please", "hi", "hello",
            "hey", "thanks", "the", "a", "an", "any", "all", "my", "our", "if", "and", "or", "but", "so", "also");
    /** Words that begin a family name together with the word after them: "Al Harbi", "bin Salman". */
    private static final Set<String> NAME_PARTICLES = Set.of("al", "el", "bin", "bint", "abu", "abd", "ibn", "ben");
    /** A capitalised word that is no part of a person's name; a run of words that holds one is not a name. */
    private static final Set<String> NOT_A_NAME = Set.of("i", "sar", "riyadh", "jeddah", "dammam", "arabic", "english",
            "saudi", "arabia", "building", "buildings", "tower", "towers", "unit", "units", "apartment", "apartments",
            "residence", "heights", "court", "gardens", "view", "lease", "leases", "contract", "contracts",
            "agreement", "rent", "payment", "payments", "tenant", "tenants", "landlord", "owner", "january",
            "february", "march", "april", "may", "june", "july", "august", "september", "october", "november",
            "december", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private static final Pattern NAME_WORD = Pattern.compile("\\p{L}[\\p{L}'-]{1," + (LONGEST_WORD - 1) + "}");
    private static final Pattern ARABIC_TENANT = Pattern.compile("(?:ال|لل|[بلكوف])?مستاجر[ه]?");
    private static final Pattern ARABIC_FAMILY_NAME = Pattern.compile("ال\\p{L}{2,28}ي");
    private static final Set<String> ARABIC_STOP_WORDS = Set.of("في", "من", "على", "الى", "عن", "مع", "حول", "هل", "ما",
            "ماذا", "كم", "هو", "هي", "التي", "الذي", "الوحده", "وحده", "عقد", "العقد", "ايجار", "الايجار", "ان", "او",
            "ثم", "قد", "لا", "يجب", "يدفع", "يحق", "يمكن", "يستطيع", "اذا", "عند", "بعد", "قبل", "خلال", "لدى");
    private static final Set<String> ARABIC_NAME_PARTICLES = Set.of("بن", "ابن", "ابو", "بنت");

    private TenantName() {
    }

    /** One word of the question: what it says without the punctuation around it, and how the punctuation ended it. */
    private record Word(String text, boolean endsClause, boolean endsSentence, boolean possessive) {

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }

        boolean looksLikeAName() {
            return NAME_WORD.matcher(text).matches();
        }

        /** An initial capital that is not an acronym's or an opening word's: a word that may be a name. */
        boolean capitalised() {
            return looksLikeAName() && Character.isUpperCase(text.charAt(0))
                    && !text.equals(text.toUpperCase(Locale.ROOT)) && !OPENERS.contains(lower());
        }

        boolean startsAFamilyName() {
            var lower = lower();
            return NAME_PARTICLES.contains(lower) || lower.startsWith("al-") || lower.startsWith("el-");
        }
    }

    static Optional<String> in(String question) {
        var words = words(question);
        for (int i = 0; i < words.size(); i++) {
            var word = words.get(i);
            if (word.endsClause() || word.possessive()) {
                continue;
            }
            Optional<String> name = Optional.empty();
            if (TENANT_WORDS.contains(word.lower())) {
                boolean aRole = i > 0 && DETERMINERS.contains(words.get(i - 1).lower());
                name = englishAfter(words, i + 1, aRole);
            } else if (ARABIC_TENANT.matcher(fold(word.text())).matches()) {
                name = arabicAfter(words, i + 1);
            }
            if (name.isPresent()) {
                return name;
            }
        }
        return capitalisedRun(words);
    }

    /**
     * The name after "tenant". A word that is not capitalised is a name only when "tenant" is not preceded by "the", "a"
     * or the like, because then it is the next word of the sentence.
     */
    private static Optional<String> englishAfter(List<Word> words, int from, boolean aRole) {
        int start = from;
        while (start < words.size() && FILLERS.contains(words.get(start).lower())) {
            start++;
        }
        if (start >= words.size()) {
            return Optional.empty();
        }
        var first = words.get(start);
        if (!first.looksLikeAName() || ENGLISH_STOP_WORDS.contains(first.lower())) {
            return Optional.empty();
        }
        if (aRole && !first.capitalised() && !endsLikeAName(words, start)) {
            return Optional.empty();
        }
        var name = new ArrayList<String>();
        name.add(first.text());
        var previous = first;
        for (int i = start + 1; i < words.size() && name.size() < MOST_WORDS && !endsName(previous); i++) {
            var next = words.get(i);
            boolean part = next.looksLikeAName() && (next.capitalised() || next.startsAFamilyName());
            if (!part || ENGLISH_STOP_WORDS.contains(next.lower())) {
                break;
            }
            name.add(next.text());
            previous = next;
        }
        return Optional.of(joined(name));
    }

    /** Whether the word at {@code index} ends a name: it ends the clause or the question, or a preposition follows. */
    private static boolean endsLikeAName(List<Word> words, int index) {
        if (endsName(words.get(index)) || index == words.size() - 1) {
            return true;
        }
        var next = words.get(index + 1);
        return next.startsAFamilyName() || PREPOSITIONS.contains(next.lower());
    }

    /** The name after "المستأجر": the next word, and a family name or a "بن" after it. */
    private static Optional<String> arabicAfter(List<Word> words, int from) {
        if (from >= words.size()) {
            return Optional.empty();
        }
        var first = words.get(from);
        if (!first.looksLikeAName() || ARABIC_STOP_WORDS.contains(fold(first.text()))) {
            return Optional.empty();
        }
        var name = new ArrayList<String>();
        name.add(first.text());
        var previous = first;
        for (int i = from + 1; i < words.size() && name.size() < MOST_WORDS && !endsName(previous); i++) {
            var next = words.get(i);
            var folded = fold(next.text());
            boolean part = next.looksLikeAName()
                    && (ARABIC_FAMILY_NAME.matcher(folded).matches() || ARABIC_NAME_PARTICLES.contains(folded)
                    || ARABIC_NAME_PARTICLES.contains(fold(previous.text())));
            if (!part || ARABIC_STOP_WORDS.contains(folded)) {
                break;
            }
            name.add(next.text());
            previous = next;
        }
        return Optional.of(String.join(" ", name));
    }

    /**
     * The first run of up to three capitalised words that is not a sentence's first word on its own and holds no place,
     * building, lease term or other word that is not a name.
     */
    private static Optional<String> capitalisedRun(List<Word> words) {
        int i = 0;
        while (i < words.size()) {
            if (!words.get(i).capitalised()) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < words.size() && end - i < MOST_WORDS && !endsName(words.get(end - 1))
                    && words.get(end).capitalised()) {
                end++;
            }
            var run = words.subList(i, end);
            boolean startsASentence = i == 0 || words.get(i - 1).endsSentence();
            boolean alone = run.size() == 1 && !run.get(0).possessive();
            if (!(startsASentence && alone) && isAName(run)) {
                return Optional.of(joined(run.stream().map(Word::text).toList()));
            }
            i = end;
        }
        return Optional.empty();
    }

    private static boolean isAName(List<Word> run) {
        var text = new StringBuilder();
        for (var word : run) {
            if (NOT_A_NAME.contains(word.lower()) || ENGLISH_STOP_WORDS.contains(word.lower())) {
                return false;
            }
            text.append(word.lower()).append(' ');
        }
        // "Late Fee" and "Notice Period" are capitalised words of the question, not a person.
        return LeaseTopic.in(text.toString()).isEmpty();
    }

    private static boolean endsName(Word word) {
        return word.endsClause() || word.possessive();
    }

    /** "Al" and the word after it are one name in the data: "Al-Harbi". */
    private static String joined(List<String> name) {
        var out = new StringBuilder(name.get(0));
        for (int i = 1; i < name.size(); i++) {
            boolean afterParticle = name.get(i - 1).equalsIgnoreCase("al") || name.get(i - 1).equalsIgnoreCase("el");
            out.append(afterParticle ? '-' : ' ').append(name.get(i));
        }
        return out.toString();
    }

    private static String fold(String text) {
        return Folding.fold(Folding.withoutDiacritics(text));
    }

    private static List<Word> words(String question) {
        var words = new ArrayList<Word>();
        for (var raw : question.strip().split("\\s+")) {
            int start = 0;
            int end = raw.length();
            while (start < end && !Character.isLetterOrDigit(raw.charAt(start))) {
                start++;
            }
            while (end > start && !Character.isLetterOrDigit(raw.charAt(end - 1))) {
                end--;
            }
            if (start == end) {
                continue;
            }
            var around = raw.substring(end);
            var text = Folding.withoutDiacritics(raw.substring(start, end));
            boolean possessive = text.length() > 2 && (text.endsWith("'s") || text.endsWith("’s"));
            if (possessive) {
                text = text.substring(0, text.length() - 2);
            }
            boolean endsSentence = around.chars().anyMatch(c -> ".?!؟".indexOf(c) >= 0);
            boolean endsClause = endsSentence || around.chars().anyMatch(c -> ",;:،".indexOf(c) >= 0);
            words.add(new Word(text.replace('’', '\''), endsClause, endsSentence, possessive));
        }
        return words;
    }
}
