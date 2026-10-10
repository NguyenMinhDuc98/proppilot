package dev.proppilot.agent.llm.offline;

import java.util.regex.Pattern;

/**
 * Spells a question the way the keyword patterns are written: lower case, one spelling for each Arabic letter and ASCII
 * digits. The folded text is as long as the text it came from, so a position found in it is a position in what the
 * visitor wrote.
 */
final class Folding {

    private static final Pattern DIACRITICS = Pattern.compile("[\\u064B-\\u0652\\u0640]");

    private Folding() {
    }

    /** Arabic vowel marks and the tatweel carry no meaning for matching. Take them out before folding. */
    static String withoutDiacritics(String text) {
        return DIACRITICS.matcher(text).replaceAll("");
    }

    static String fold(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.append(foldChar(text.charAt(i)));
        }
        return out.toString();
    }

    private static char foldChar(char c) {
        return switch (c) {
            case 'أ', 'إ', 'آ' -> 'ا';
            case 'ى', 'ئ' -> 'ي';
            case 'ؤ' -> 'و';
            case 'ة' -> 'ه';
            case '٫' -> '.';
            case '٬' -> ',';
            case '’' -> '\'';
            case '−' -> '-';
            default -> Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : Character.toLowerCase(c);
        };
    }
}
