package dev.proppilot.guardrail;

/**
 * Reads the figures, unit codes and citations out of a text in one pass. It is written by hand, with no regular
 * expression over the text: a pattern with a repeated group recurses once for each repetition, and an answer, a tool
 * result or a question can be as long as a hostile caller makes it. Everything here looks a fixed distance ahead, so
 * the work is proportional to the length of the text.
 *
 * <p>A figure is a run of digits of any script, with thousands separators (a comma, or the Arabic one) and a decimal
 * mark (a point, or the Arabic one); what stands around it (a currency word, a percent sign, a sign) is not part of it.
 * Its normal form has ASCII digits, no separators, no leading zeros and no trailing zeros of the decimals, so that
 * {@code 4,950}, {@code 4950.00} and {@code ٤٩٥٠} are one figure.
 *
 * <p>Two modes: the text of an answer, in which a citation marker is a claim of its own and the digits of a unit code
 * or a marker are not read as a figure; and evidence, in which every digit run is a figure and there are no markers,
 * because a marker inside a lease clause is quoted text, not a clause that was returned.
 */
final class ClaimScanner {

    /** Receives each claim found: what was written, its normal form, and where it starts. */
    interface Claims {
        void found(ClaimKind kind, String written, String normalised, int at);
    }

    private static final String REF_START = "LSE-";
    private static final String MARKER_START = "[" + REF_START;
    private static final int MAX_MARKER_LENGTH = 64;
    private static final int CODE_DIGITS = 3;
    private static final int GROUP_DIGITS = 3;
    /** A list item is numbered with at most this many digits; a longer number at the start of a line is a figure. */
    private static final int MAX_LIST_NUMBER_DIGITS = 3;
    private static final char ARABIC_DECIMAL_MARK = '٫';
    private static final char ARABIC_THOUSANDS_SEPARATOR = '٬';

    private final String text;
    private final boolean answer;
    private final Claims claims;
    private int at;
    private boolean atLineStart = true;

    private ClaimScanner(String text, boolean answer, Claims claims) {
        this.text = text;
        this.answer = answer;
        this.claims = claims;
    }

    /** Figures, unit codes and citation markers of an answer. */
    static void ofAnswer(String text, Claims claims) {
        new ClaimScanner(text, true, claims).scan();
    }

    /** Figures and unit codes of something the model was given. */
    static void ofEvidence(String text, Claims claims) {
        new ClaimScanner(text, false, claims).scan();
    }

    private void scan() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\n') {
                atLineStart = true;
                at++;
            } else if (c == ' ' || c == '\t' || c == '>') {
                at++;
            } else {
                boolean startsLine = atLineStart;
                atLineStart = false;
                scanFrom(c, startsLine);
            }
        }
    }

    private void scanFrom(char c, boolean startsLine) {
        if (answer && c == '[' && marker()) {
            return;
        }
        if (isAsciiLetter(c) && unitCode()) {
            return;
        }
        if (Character.digit(c, 10) >= 0) {
            figure(startsLine);
        } else {
            at++;
        }
    }

    /**
     * {@code [LSE-...]}: each ref in the brackets is a citation claim. Brackets that are not closed within
     * {@value #MAX_MARKER_LENGTH} characters are not a marker.
     */
    private boolean marker() {
        if (!text.startsWith(MARKER_START, at)) {
            return false;
        }
        int limit = Math.min(text.length(), at + MAX_MARKER_LENGTH);
        int close = at + 1;
        while (close < limit && text.charAt(close) != ']') {
            close++;
        }
        if (close == limit) {
            return false;
        }
        int tokenStart = at + 1;
        for (int i = at + 1; i <= close; i++) {
            char c = text.charAt(i);
            if (i == close || c == ',' || c == ';' || c == ' ') {
                if (i > tokenStart && text.startsWith(REF_START, tokenStart)) {
                    var ref = text.substring(tokenStart, i);
                    claims.found(ClaimKind.CITATION, ref, ref, tokenStart);
                }
                tokenStart = i + 1;
            }
        }
        at = close + 1;
        return true;
    }

    /**
     * A letter, a hyphen and three digits, with no letter or digit before them and no digit after: {@code A-203},
     * also as the end of {@code LSE-A-203}. In evidence its digits are read as a figure next.
     */
    private boolean unitCode() {
        if (at > 0 && isAsciiLetterOrDigit(text.charAt(at - 1))) {
            return false;
        }
        int end = at + 2 + CODE_DIGITS;
        if (end > text.length() || text.charAt(at + 1) != '-') {
            return false;
        }
        var code = new StringBuilder(2 + CODE_DIGITS);
        code.append(Character.toUpperCase(text.charAt(at))).append('-');
        for (int i = at + 2; i < end; i++) {
            int digit = Character.digit(text.charAt(i), 10);
            if (digit < 0) {
                return false;
            }
            code.append((char) ('0' + digit));
        }
        if (end < text.length() && Character.digit(text.charAt(end), 10) >= 0) {
            return false;
        }
        claims.found(ClaimKind.UNIT_CODE, text.substring(at, end), code.toString(), at);
        at = answer ? end : at + 1;
        return true;
    }

    private void figure(boolean startsLine) {
        int start = at;
        var whole = new StringBuilder();
        int end = digits(start, whole);
        boolean plain = true;
        while (isGroupAt(end)) {
            plain = false;
            end = digits(end + 1, whole);
        }
        var decimals = new StringBuilder();
        if (isDecimalPointAt(end)) {
            plain = false;
            end = digits(end + 1, decimals);
        }
        at = end;
        if (answer && startsLine && plain && whole.length() <= MAX_LIST_NUMBER_DIGITS && isListMarkerEnd(end)) {
            return;
        }
        claims.found(ClaimKind.NUMBER, text.substring(start, end), normal(whole, decimals), start);
    }

    /** Appends the digits that run from {@code from} as ASCII digits and returns where the run ends. */
    private int digits(int from, StringBuilder into) {
        int i = from;
        while (i < text.length()) {
            int digit = Character.digit(text.charAt(i), 10);
            if (digit < 0) {
                break;
            }
            into.append((char) ('0' + digit));
            i++;
        }
        return i;
    }

    /** A decimal mark at {@code i} with a digit after it. */
    private boolean isDecimalPointAt(int i) {
        return i + 1 < text.length() && isDecimalMark(text.charAt(i)) && Character.digit(text.charAt(i + 1), 10) >= 0;
    }

    /** A thousands separator at {@code i}, followed by exactly three digits. */
    private boolean isGroupAt(int i) {
        if (i + GROUP_DIGITS >= text.length() || !isThousandsSeparator(text.charAt(i))) {
            return false;
        }
        for (int k = 1; k <= GROUP_DIGITS; k++) {
            if (Character.digit(text.charAt(i + k), 10) < 0) {
                return false;
            }
        }
        int after = i + GROUP_DIGITS + 1;
        return after >= text.length() || Character.digit(text.charAt(after), 10) < 0;
    }

    /** {@code 1.} or {@code 2)} followed by a space: the number of an item of a list, not a figure. */
    private boolean isListMarkerEnd(int end) {
        return end + 1 < text.length() && (text.charAt(end) == '.' || text.charAt(end) == ')')
                && text.charAt(end + 1) == ' ';
    }

    private static String normal(StringBuilder whole, StringBuilder decimals) {
        int first = 0;
        while (first < whole.length() - 1 && whole.charAt(first) == '0') {
            first++;
        }
        int last = decimals.length();
        while (last > 0 && decimals.charAt(last - 1) == '0') {
            last--;
        }
        var normal = whole.substring(first);
        return last == 0 ? normal : normal + "." + decimals.substring(0, last);
    }

    private static boolean isThousandsSeparator(char c) {
        return c == ',' || c == ARABIC_THOUSANDS_SEPARATOR;
    }

    private static boolean isDecimalMark(char c) {
        return c == '.' || c == ARABIC_DECIMAL_MARK;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }
}
