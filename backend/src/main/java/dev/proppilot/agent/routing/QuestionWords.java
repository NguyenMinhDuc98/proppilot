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

    private final List<String> words;

    private QuestionWords(List<String> words) {
        this.words = words;
    }

    static QuestionWords of(String folded) {
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        for (int i = 0; i < folded.length() && words.size() < MOST_WORDS; ) {
            int codePoint = folded.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty() && words.size() < MOST_WORDS) {
            words.add(word.toString());
        }
        return new QuestionWords(words);
    }

    int size() {
        return words.size();
    }

    String get(int index) {
        return words.get(index);
    }

    boolean has(Vocabulary vocabulary) {
        return indexOf(vocabulary) >= 0;
    }

    /** The position of the first word of the vocabulary, or -1. */
    int indexOf(Vocabulary vocabulary) {
        for (int i = 0; i < words.size(); i++) {
            if (vocabulary.matches(words.get(i))) {
                return i;
            }
        }
        return -1;
    }

    boolean hasInOrder(String first, String second) {
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals(first) && words.get(i + 1).equals(second)) {
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
            var word = words.get(i);
            boolean attached = word.startsWith("و") && word.length() > 3 && !NOT_AND.contains(word);
            if (word.equals("and") || word.equals("و") || attached) {
                return i;
            }
        }
        return words.size();
    }

    /** These words without the ones from {@code from} up to, and not including, {@code to}. */
    QuestionWords without(int from, int to) {
        var kept = new ArrayList<>(words.subList(0, from));
        kept.addAll(words.subList(to, words.size()));
        return new QuestionWords(kept);
    }
}
