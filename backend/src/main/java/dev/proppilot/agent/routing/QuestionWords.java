package dev.proppilot.agent.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The words of a folded question: runs of letters and digits, in order. The rules ask for words and never read the
 * question as text, so there is no regular expression to run over what a visitor typed. At most {@link #MOST_WORDS}
 * words are kept, which is more than any question the app accepts has.
 */
final class QuestionWords {

    static final int MOST_WORDS = 400;
    /** Words that begin with "و" and are not "and" attached to the next word. */
    private static final Set<String> NOT_AND = Set.of("وحدات", "وحده", "وديعه", "وثيقه", "وثائق", "وقت", "واحد", "وضع",
            "وصف");

    /** A word, and where it stands in the text it was read from. */
    private record Word(String text, int start, int end) {
    }

    private final String text;
    private final List<Word> words;

    private QuestionWords(String text, List<Word> words) {
        this.text = text;
        this.words = words;
    }

    static QuestionWords of(String folded) {
        var words = new ArrayList<Word>();
        int start = nextWordFrom(folded, 0);
        while (start >= 0 && words.size() < MOST_WORDS) {
            int end = endOfWordAt(folded, start);
            words.add(new Word(folded.substring(start, end), start, end));
            start = nextWordFrom(folded, end);
        }
        return new QuestionWords(folded, words);
    }

    private static int nextWordFrom(String text, int from) {
        for (int i = from; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            if (Character.isLetterOrDigit(text.codePointAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfWordAt(String text, int start) {
        int i = start;
        while (i < text.length() && Character.isLetterOrDigit(text.codePointAt(i))) {
            i += Character.charCount(text.codePointAt(i));
        }
        return i;
    }

    /** The text the words were read from, so that a check that needs the text can read the same question. */
    String text() {
        return text;
    }

    int size() {
        return words.size();
    }

    String get(int index) {
        return words.get(index).text();
    }

    boolean has(Vocabulary vocabulary) {
        return indexOf(vocabulary) >= 0;
    }

    /** The position of the first word of the vocabulary, or -1. */
    int indexOf(Vocabulary vocabulary) {
        for (int i = 0; i < words.size(); i++) {
            if (vocabulary.matches(words.get(i).text())) {
                return i;
            }
        }
        return -1;
    }

    boolean hasInOrder(String first, String second) {
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).text().equals(first) && words.get(i + 1).text().equals(second)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where an "and" next comes after the word at {@code index}, or the number of words when none does. It is the
     * English word, or the Arabic "و", which stands alone or is written in front of the next word ("الإشغال والإيجار").
     */
    int andAfter(int index) {
        for (int i = index + 1; i < words.size(); i++) {
            var word = words.get(i).text();
            boolean attached = word.startsWith("و") && word.length() > 3 && !NOT_AND.contains(word);
            if (word.equals("and") || word.equals("و") || attached) {
                return i;
            }
        }
        return words.size();
    }

    /**
     * The question without the words from {@code from} up to, and not including, {@code to}. What stands around them,
     * the punctuation too, is left as it was written.
     */
    QuestionWords without(int from, int to) {
        return of(text.substring(0, words.get(from).start()) + " " + text.substring(words.get(to - 1).end()));
    }
}
