package dev.proppilot.rag.retrieve;

import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The words of a question, spelled the way the stored chunks are (see {@link Folding}) and cut down to letters and
 * digits, so what reaches a full text query is a plain list of words and never a piece of the question itself:
 * {@code | & ! ( ) : * < > ' "} and a backslash have no way in. At most {@link #MAX_TERMS} different words are kept.
 *
 * <p>Words in Arabic script go to the Arabic text search configuration and the others to the English one. Each
 * configuration drops the stop words of its own language only, so a word sent to both would match nearly every
 * chunk through "the" or "في". The work is one pass over the question, with no regular expression.
 */
final class QueryTerms {

    static final int MAX_TERMS = 32;
    /** Nothing a question says is a longer word, and a word this long is not a word. */
    private static final int MAX_TERM_LENGTH = 64;
    private static final String OR = " | ";

    private final List<String> arabic;
    private final List<String> other;
    private final List<String> all;

    private QueryTerms(List<String> arabic, List<String> other, List<String> all) {
        this.arabic = arabic;
        this.other = other;
        this.all = all;
    }

    static QueryTerms of(String question) {
        var folded = Folding.fold(Folding.withoutDiacritics(question));
        var terms = new LinkedHashSet<String>();
        var word = new StringBuilder();
        for (int i = 0; i < folded.length() && terms.size() < MAX_TERMS; ) {
            int codePoint = folded.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else {
                addTerm(terms, word);
            }
        }
        addTerm(terms, word);
        return partition(terms);
    }

    /** The words as one query for {@code to_tsquery('arabic', ...)}, or an empty text when there are none. */
    String arabicTsQuery() {
        return String.join(OR, arabic);
    }

    /** The words as one query for {@code to_tsquery('english', ...)}, or an empty text when there are none. */
    String otherTsQuery() {
        return String.join(OR, other);
    }

    /** All the words, as the text trigram similarity is measured for. */
    String text() {
        return String.join(" ", all);
    }

    boolean isEmpty() {
        return all.isEmpty();
    }

    /** A single letter says nothing; a single digit may be a clause number. */
    private static void addTerm(Set<String> terms, StringBuilder word) {
        if (word.length() > 0 && word.length() <= MAX_TERM_LENGTH && terms.size() < MAX_TERMS
                && (word.length() > 1 || Character.isDigit(word.charAt(0)))) {
            terms.add(word.toString());
        }
        word.setLength(0);
    }

    private static QueryTerms partition(Set<String> terms) {
        var arabic = new ArrayList<String>();
        var other = new ArrayList<String>();
        for (var term : terms) {
            (term.codePoints().anyMatch(QueryTerms::isArabic) ? arabic : other).add(term);
        }
        return new QueryTerms(arabic, other, List.copyOf(terms));
    }

    private static boolean isArabic(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC;
    }
}
