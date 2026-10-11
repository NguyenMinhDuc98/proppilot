package dev.proppilot.rag.pdf;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;

/**
 * Turns a line of logical text into what a PDF font has to be given to show it right to left: Arabic letters in their
 * joined shapes, and the characters of the line in the order they are drawn from left to right. A PDF viewer draws the
 * glyphs in the order they are given and knows nothing of either, so a line that is not prepared shows as separate
 * letters in reverse.
 */
final class RightToLeftText {

    private RightToLeftText() {
    }

    /** One line. Shape and reorder after the text has been broken into lines, never before. */
    static String visual(String logicalLine) {
        try {
            var shaping = new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL);
            var shaped = shaping.shape(logicalLine);
            return new Bidi(shaped, Bidi.DIRECTION_RIGHT_TO_LEFT).writeReordered(Bidi.DO_MIRRORING);
        } catch (ArabicShapingException e) {
            throw new IllegalArgumentException("Cannot shape the line: " + logicalLine, e);
        }
    }
}
