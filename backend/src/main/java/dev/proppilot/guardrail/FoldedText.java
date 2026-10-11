package dev.proppilot.guardrail;

import dev.proppilot.text.Folding;

/**
 * A text spelled the way names are compared: lower case, one spelling for each Arabic letter, no Arabic vowel marks and
 * one space for each run of white space. It remembers where every character was written, so a match in it can be
 * given back as the answer wrote it.
 */
final class FoldedText {

    private final String raw;
    private final String text;
    /** {@code origin[k]} is where character {@code k} of the folded text stands in the raw one; the last is its end. */
    private final int[] origin;

    private FoldedText(String raw, String text, int[] origin) {
        this.raw = raw;
        this.text = text;
        this.origin = origin;
    }

    /** One pass, whatever the length: {@link Folding#fold} keeps the length, so the positions line up. */
    static FoldedText of(String raw) {
        var folded = Folding.fold(raw);
        var out = new StringBuilder(folded.length());
        var origin = new int[folded.length() + 1];
        boolean afterSpace = false;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (Folding.isDiacritic(c)) {
                continue;
            }
            boolean space = Character.isWhitespace(c) || Character.isSpaceChar(c);
            if (space && afterSpace) {
                continue;
            }
            afterSpace = space;
            origin[out.length()] = i;
            out.append(space ? ' ' : c);
        }
        origin[out.length()] = raw.length();
        return new FoldedText(raw, out.toString(), origin);
    }

    String text() {
        return text;
    }

    /** Where the character at {@code index} of the folded text was written in the raw one. */
    int positionOf(int index) {
        return origin[index];
    }

    /** The raw text that the folded text from {@code from} up to {@code to} stands for. */
    String written(int from, int to) {
        return raw.substring(origin[from], origin[to]);
    }

    /**
     * Where {@code name} first stands in {@code text} at or after {@code from}, or -1. A name does not count when it
     * runs into a Latin letter or digit on either side ("Ali Khan" is not in "Mali Khan"); Arabic letters may be
     * attached to it, because Arabic writes a preposition or a conjunction onto the word that follows it.
     */
    static int indexOfName(String text, String name, int from) {
        int at = text.indexOf(name, from);
        while (at >= 0) {
            int end = at + name.length();
            boolean glued = (at > 0 && isLatinLetterOrDigit(text.charAt(at - 1)))
                    || (end < text.length() && isLatinLetterOrDigit(text.charAt(end)));
            if (!glued) {
                return at;
            }
            at = text.indexOf(name, at + 1);
        }
        return -1;
    }

    private static boolean isLatinLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
