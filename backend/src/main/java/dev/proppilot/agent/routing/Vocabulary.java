package dev.proppilot.agent.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of words in English and Arabic that a rule looks for, written in the folded spelling of
 * {@link dev.proppilot.text.Folding}. An entry is a word that has to match as it is ({@code late}), a stem that a word
 * has to start with ({@code expir*}) or a fragment a word has to contain ({@code *نته}, for the many forms of an Arabic
 * verb). An Arabic word also matches without the article or the one-letter prefix in front of it, so "الوحدات",
 * "للوحدات" and "والوحدات" are all "وحدات".
 */
final class Vocabulary {

    /** Longest first, so that "وال" comes off a word before "و" does. */
    private static final List<String> ARABIC_PREFIXES = List.of("وال", "بال", "كال", "فال", "لل", "ال", "و", "ف", "ب", "ل",
            "ك");
    private static final int SHORTEST_WORD_LEFT = 2;

    private final Set<String> exact = new HashSet<>();
    private final List<String> stems = new ArrayList<>();
    private final List<String> fragments = new ArrayList<>();

    static Vocabulary of(String... entries) {
        var vocabulary = new Vocabulary();
        for (var entry : entries) {
            if (entry.startsWith("*")) {
                vocabulary.fragments.add(entry.substring(1));
            } else if (entry.endsWith("*")) {
                vocabulary.stems.add(entry.substring(0, entry.length() - 1));
            } else {
                vocabulary.exact.add(entry);
            }
        }
        return vocabulary;
    }

    boolean matches(String word) {
        if (matchesAsWritten(word)) {
            return true;
        }
        for (var prefix : ARABIC_PREFIXES) {
            if (word.startsWith(prefix) && word.length() - prefix.length() >= SHORTEST_WORD_LEFT
                    && matchesAsWritten(word.substring(prefix.length()))) {
                return true;
            }
        }
        return fragments.stream().anyMatch(word::contains);
    }

    private boolean matchesAsWritten(String word) {
        return exact.contains(word) || stems.stream().anyMatch(word::startsWith);
    }
}
