package dev.proppilot.rag.chunk;

import dev.proppilot.text.Folding;
import java.util.Set;

/**
 * Lines of a lease document that are not its content: the page number in the footer, and the signature block that
 * closes it. The footer reads "Page 2 of 3" or "صفحة 2 من 3", and is matched by its words and numbers whatever order
 * extraction put them in.
 */
final class PageFurniture {

    private static final int MAX_LENGTH = 40;
    private static final int MAX_FOOTER_WORDS = 5;
    private static final Set<String> FOOTER_WORDS = Set.of("page", "of", "صفحه", "من");
    private static final Set<String> SIGNATURES_HEADINGS = Set.of("signatures",
            Folding.fold("التوقيعات"));

    private PageFurniture() {
    }

    static boolean isPageFooter(String line) {
        if (line.length() > MAX_LENGTH) {
            return false;
        }
        var words = Folding.fold(line).split(" ");
        boolean hasFooterWord = false;
        if (words.length > MAX_FOOTER_WORDS) {
            return false;
        }
        for (var word : words) {
            if (FOOTER_WORDS.contains(word)) {
                hasFooterWord = true;
            } else if (!isNumber(word)) {
                return false;
            }
        }
        return hasFooterWord;
    }

    /** The line that opens the signature block. Nothing after it belongs to a clause. */
    static boolean isSignaturesHeading(String line) {
        return line.length() <= MAX_LENGTH && SIGNATURES_HEADINGS.contains(Folding.fold(line));
    }

    private static boolean isNumber(String word) {
        return !word.isEmpty() && word.chars().allMatch(Character::isDigit);
    }
}
