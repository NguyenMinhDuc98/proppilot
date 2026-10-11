package dev.proppilot.rag.chunk;

import dev.proppilot.text.Folding;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognises the heading line of a numbered clause, in English ({@code 7. Late payment}) and in Arabic
 * ({@code البند 7: غرامة التأخير}). Digits may be Latin or Arabic-Indic.
 *
 * <p>A text extracted from a PDF may put a Latin token that ended a right-to-left line at the start of it, so the
 * Arabic form allows up to two such tokens before the word for "clause", and puts them back after the title. Nothing
 * else about where Latin tokens land is relied on.
 *
 * <p>Headings are short, and the patterns only ever see a line of at most {@link #MAX_LINE_LENGTH} characters: whatever
 * a document holds, matching costs a fixed amount of work. Their repeats are bounded for the same reason.
 */
final class ClauseHeading {

    static final int MAX_LINE_LENGTH = 100;
    private static final int MAX_NUMBER_DIGITS = 2;

    private static final Pattern ENGLISH = Pattern.compile("(\\d{1," + MAX_NUMBER_DIGITS + "})\\.\\s+(\\S.{0,90})");
    private static final Pattern ARABIC = Pattern.compile(
            "((?:[a-z0-9#._-]{1,40}\\s+){0,2})(?:البند|بند)\\s+(\\d{1,"
                    + MAX_NUMBER_DIGITS + "})\\s*[:\\-–—]\\s*(\\S.{0,90})");

    private ClauseHeading() {
    }

    /**
     * @param number the clause number
     * @param title the clause title as written, without the number
     */
    record Heading(int number, String title) {
    }

    /**
     * @param line a line of the document, with extraction artefacts already removed
     * @return the heading the line is, empty when it is any other line
     */
    static Optional<Heading> parse(String line) {
        if (line.length() > MAX_LINE_LENGTH) {
            return Optional.empty();
        }
        var plain = Folding.withoutDiacritics(line);
        var folded = Folding.fold(plain);
        var english = ENGLISH.matcher(folded);
        if (english.matches()) {
            return heading(english.group(1), plain.substring(english.start(2)));
        }
        var arabic = ARABIC.matcher(folded);
        if (arabic.matches()) {
            var movedToStart = plain.substring(arabic.start(1), arabic.end(1)).strip();
            var title = plain.substring(arabic.start(3));
            return heading(arabic.group(2), movedToStart.isEmpty() ? title : title + " " + movedToStart);
        }
        return Optional.empty();
    }

    private static Optional<Heading> heading(String number, String title) {
        int value = Integer.parseInt(number);
        var stripped = title.strip();
        if (value < 1 || endsLikeASentence(stripped)) {
            return Optional.empty();
        }
        return Optional.of(new Heading(value, stripped));
    }

    /** A title is a noun phrase. A line that ends like a sentence is a paragraph that happens to start with a number. */
    private static boolean endsLikeASentence(String title) {
        char last = title.charAt(title.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '؟' || last == ',' || last == ';';
    }
}
