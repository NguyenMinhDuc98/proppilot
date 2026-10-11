package dev.proppilot.rag.chunk;

/**
 * A cheap guess of how many tokens a text takes, to size chunks and to budget embedding calls. It is not a tokenizer
 * and it is not a bill.
 *
 * <p>Common tokenizers average about 4 characters to a token for English and about 3 for Arabic script, which they
 * split into smaller pieces. Whitespace counts as a character. So an Arabic letter weighs 4 and any other character 3,
 * and a token is 12 weight: {@code ceil(arabic / 3 + other / 4)} without fractions.
 */
public final class TokenEstimate {

    /** The most a single character weighs: a slice of this many characters per unit of budget never goes over it. */
    static final int MAX_CHARACTER_WEIGHT = 4;
    private static final int ARABIC_WEIGHT = 4;
    private static final int OTHER_WEIGHT = 3;
    private static final int WEIGHT_PER_TOKEN = 12;

    private TokenEstimate() {
    }

    public static int of(String text) {
        return tokens(weight(text));
    }

    /** The weight of a text. Weights add up, so a chunker can size a run of sentences without measuring it again. */
    static long weight(String text) {
        long weight = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            weight += Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC
                    ? ARABIC_WEIGHT : OTHER_WEIGHT;
            i += Character.charCount(codePoint);
        }
        return weight;
    }

    static int tokens(long weight) {
        return (int) ((weight + WEIGHT_PER_TOKEN - 1) / WEIGHT_PER_TOKEN);
    }

    /** The weight of this many tokens. */
    static long weightOf(int tokens) {
        return (long) tokens * WEIGHT_PER_TOKEN;
    }
}
