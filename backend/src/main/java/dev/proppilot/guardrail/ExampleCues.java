package dev.proppilot.guardrail;

/**
 * Whether a unit code of an answer is given as an example, as in "for example A-203" or "مثل A-203", which asks the
 * user for a code and states nothing about a unit. A claim is checked against what the run's tools returned; an
 * example is not a claim, so it is not held against the evidence. The figures and names around it still are.
 *
 * <p>A cue counts when it stands in the same sentence, a short distance before the code, and so does each code that
 * follows an example in a list ("such as A-203, B-415 or C-101"). The scan is by hand and looks at most
 * {@value #WINDOW} characters back, so its cost does not depend on how long the answer is.
 */
final class ExampleCues {

    private static final int WINDOW = 56;
    private static final String[] CUES = {"e.g.", "e.g", "for example", "for instance", "such as", "something like",
        "looks like", "look like", "مثل", "على سبيل المثال", "نحو"};
    private static final String[] LIST_WORDS = {"and", "or", "&", "و", "أو"};

    private ExampleCues() {
    }

    /** A cue stands before {@code at}, in the same sentence, within the window. */
    static boolean introduces(String text, int at) {
        int from = Math.max(0, at - WINDOW);
        for (int i = at - 1; i >= from; i--) {
            for (var cue : CUES) {
                int end = i + cue.length();
                if (end <= at && text.regionMatches(true, i, cue, 0, cue.length())
                        && !endsASentenceBetween(text, end, at)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Only separators and list words lie between the end of an example and the next code. */
    static boolean continuesList(String text, int exampleEnd, int at) {
        if (exampleEnd < 0 || exampleEnd >= at || at - exampleEnd > WINDOW) {
            return false;
        }
        int i = exampleEnd;
        while (i < at) {
            char c = text.charAt(i);
            if (c == ' ' || c == ',' || c == '،' || c == ';' || c == '\t') {
                i++;
                continue;
            }
            int next = i;
            while (next < at && " ,،;\t".indexOf(text.charAt(next)) < 0) {
                next++;
            }
            if (!isListWord(text, i, next)) {
                return false;
            }
            i = next;
        }
        return true;
    }

    private static boolean isListWord(String text, int from, int to) {
        for (var word : LIST_WORDS) {
            if (word.length() == to - from && text.regionMatches(true, from, word, 0, word.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsASentenceBetween(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '!' || c == '?' || c == '؟') {
                return true;
            }
            if (c == '.' && (i + 1 >= to || text.charAt(i + 1) == ' ')) {
                return true;
            }
        }
        return false;
    }
}
