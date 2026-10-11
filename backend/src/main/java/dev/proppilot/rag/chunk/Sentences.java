package dev.proppilot.rag.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a text into sentences: after a full stop, question mark or exclamation mark (Latin or Arabic) that is followed by
 * white space or the end of the text. A full stop inside a number such as {@code 247.50} ends nothing. One pass with no
 * regular expression, so a text of any length and any shape costs time in proportion to its length.
 */
final class Sentences {

    private Sentences() {
    }

    static List<String> split(String text) {
        var sentences = new ArrayList<String>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            if (!isTerminator(text.charAt(i))) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < text.length() && (isTerminator(text.charAt(end)) || isCloser(text.charAt(end)))) {
                end++;
            }
            if (end == text.length() || Character.isWhitespace(text.charAt(end))) {
                add(sentences, text, start, end);
                start = end;
            }
            i = end;
        }
        add(sentences, text, start, text.length());
        return sentences;
    }

    private static void add(List<String> sentences, String text, int start, int end) {
        var sentence = text.substring(start, end).strip();
        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == '؟';
    }

    /** A quote or bracket that closes a sentence: {@code ... allowed.") Next}. */
    private static boolean isCloser(char c) {
        return c == '"' || c == '\'' || c == ')' || c == '”' || c == '»';
    }
}
