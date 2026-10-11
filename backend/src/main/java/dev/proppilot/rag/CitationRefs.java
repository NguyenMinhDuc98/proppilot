package dev.proppilot.rag;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows how a clause of a lease document is named in a citation.
 *
 * <p>A <em>ref</em> is the document code, {@code #}, the clause number and, when a long clause was split, the letter
 * of the part: {@code LSE-A-504#7} or {@code LSE-A-504#7a}. Clause 0 is the preamble. A <em>marker</em> is a ref in
 * square brackets, {@code [LSE-A-504#7]}, which is how an answer cites it. Letters run a, b, ... z, aa, ab, ...
 *
 * <p>Every pattern here is bounded: nothing repeats without a limit, so text of any length costs a fixed amount of
 * work for each place a marker could start.
 */
public final class CitationRefs {

    /** The code of a lease document: {@code LSE-}, the building letters and the unit number, as in LSE-A-504. */
    public static final String DOCUMENT_CODE = "LSE-[A-Z]{1,8}-[0-9]{1,5}";

    /**
     * A marker in running text. Group 1 is the document code, group 2 the clause number and group 3 the part letters,
     * which is null when the clause is whole. Markers that stand next to each other are found one by one.
     */
    public static final Pattern MARKER = Pattern.compile("\\[" + tokenPattern() + "]");

    private static final Pattern REF = Pattern.compile(tokenPattern());
    private static final char SEPARATOR = '#';
    private static final int MAX_PART_LETTERS = 4;
    private static final int LETTERS = 'z' - 'a' + 1;
    /** Longer than any ref there can be, so a longer text is not looked at. */
    private static final int MAX_REF_LENGTH = 64;

    private CitationRefs() {
    }

    /**
     * The parts of a ref.
     *
     * @param part the letters of the part, or an empty text when the clause is whole
     */
    public record Ref(String documentCode, int clauseNumber, String part) {

        /** The ref as it is written, without brackets. */
        public String token() {
            return documentCode + SEPARATOR + clauseNumber + part;
        }
    }

    /** The ref of a whole clause. */
    public static String format(String documentCode, int clauseNumber) {
        return new Ref(documentCode, clauseNumber, "").token();
    }

    /**
     * The ref of one part of a clause that was split.
     *
     * @param partIndex the position of the part among the parts of the clause, from 0
     */
    public static String format(String documentCode, int clauseNumber, int partIndex) {
        return new Ref(documentCode, clauseNumber, partLetters(partIndex)).token();
    }

    /** The ref written as a citation marker: in square brackets. */
    public static String marker(String ref) {
        return "[" + ref + "]";
    }

    /**
     * The text with each marker in it written as its ref in parentheses. Text that is quoted from a document can then
     * not carry a citation of its own.
     */
    public static String withoutMarkers(String text) {
        return MARKER.matcher(text).replaceAll(found -> Matcher.quoteReplacement(
                "(" + found.group().substring(1, found.group().length() - 1) + ")"));
    }

    /**
     * Reads a ref back.
     *
     * @param token a ref without brackets, exactly as it is written
     * @return empty when the text is not a ref, whatever else it is
     */
    public static Optional<Ref> parse(String token) {
        if (token == null || token.length() > MAX_REF_LENGTH) {
            return Optional.empty();
        }
        var matcher = REF.matcher(token);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Ref(matcher.group(1), Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? "" : matcher.group(3)));
    }

    /** a, b, ... z, aa, ab, ...: the letters of the part at this position, counted from 0. */
    static String partLetters(int partIndex) {
        if (partIndex < 0) {
            throw new IllegalArgumentException("A part has a position of 0 or more: " + partIndex);
        }
        var letters = new StringBuilder();
        for (int n = partIndex + 1; n > 0; n /= LETTERS) {
            n--;
            letters.insert(0, (char) ('a' + n % LETTERS));
        }
        if (letters.length() > MAX_PART_LETTERS) {
            throw new IllegalArgumentException("A ref cannot name part " + partIndex);
        }
        return letters.toString();
    }

    /** Clause numbers have one or two digits, with no leading zero; the preamble is 0. */
    private static String tokenPattern() {
        return "(" + DOCUMENT_CODE + ")" + SEPARATOR + "(0|[1-9][0-9]?)([a-z]{1," + MAX_PART_LETTERS + "})?";
    }
}
