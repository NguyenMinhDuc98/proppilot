package dev.proppilot.rag.chunk;

import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cuts the extracted text of a lease document into the chunks that are searched and cited: a preamble ({@code #0}) and
 * one chunk for each numbered clause ({@code #7}). A clause of more than {@link #MAX_CHUNK_TOKENS} estimated tokens is
 * split on sentence boundaries into parts ({@code #7a}, {@code #7b}, ...), each starting with the last sentences of the
 * part before it, about {@link #OVERLAP_TOKENS} tokens of them, so an answer that straddles a cut is whole in one part.
 *
 * <p>Every chunk begins with its clause heading, so a part reads on its own. The page footers and the signature block
 * are not part of any chunk, and a clause number is accepted only when it is higher than the one before it, so a
 * paragraph that starts with a number cannot make a clause of its own.
 *
 * <p>The text may be of any size and shape. Nothing here recurses or runs a regular expression over more than a
 * heading's length of text; the work is linear in the size of the text.
 */
@Component
public class LeaseChunker {

    public static final int MAX_CHUNK_TOKENS = 500;
    public static final int OVERLAP_TOKENS = 50;

    private static final int PREAMBLE = 0;
    private static final char TATWEEL = 'ـ';
    private static final long SPACE_WEIGHT = TokenEstimate.weight(" ");

    /**
     * @param documentCode the document's code, such as {@code LSE-A-504}; it starts every ref
     * @param pages the text of each page in page order, as {@link dev.proppilot.rag.pdf.PdfTextExtractor} returns it
     */
    public List<LeaseChunk> chunk(String documentCode, List<String> pages) {
        var chunks = new ArrayList<LeaseChunk>();
        for (var section : sections(pages)) {
            var bodies = bodies(section);
            for (int part = 0; part < bodies.size(); part++) {
                var content = section.headingLine() == null ? bodies.get(part)
                        : (section.headingLine() + "\n" + bodies.get(part)).strip();
                var suffix = bodies.size() > 1 ? letters(part) : "";
                chunks.add(new LeaseChunk(documentCode + "#" + section.number() + suffix, chunks.size(),
                        section.number() == PREAMBLE ? null : section.number(), section.title(), section.page(),
                        content, Folding.fold(Folding.withoutDiacritics(content)), TokenEstimate.of(content)));
            }
        }
        return chunks;
    }

    /** The preamble has no heading line and no title, and is number 0. */
    private record Section(int number, String title, String headingLine, int page, List<String> lines) {
    }

    private record Piece(String text, long weight) {
    }

    private static List<Section> sections(List<String> pages) {
        var sections = new ArrayList<Section>();
        Section current = null;
        int lastNumber = PREAMBLE;
        for (int index = 0; index < pages.size(); index++) {
            for (var rawLine : pages.get(index).split("\n")) {
                var line = cleaned(rawLine);
                if (line.isEmpty() || PageFurniture.isPageFooter(line)) {
                    continue;
                }
                if (PageFurniture.isSignaturesHeading(line)) {
                    return sections;
                }
                var heading = ClauseHeading.parse(line);
                if (heading.isPresent() && heading.get().number() > lastNumber) {
                    lastNumber = heading.get().number();
                    current = new Section(lastNumber, heading.get().title(), line, index + 1, new ArrayList<>());
                    sections.add(current);
                } else {
                    if (current == null) {
                        current = new Section(PREAMBLE, null, null, index + 1, new ArrayList<>());
                        sections.add(current);
                    }
                    current.lines().add(line);
                }
            }
        }
        return sections;
    }

    /**
     * Drops what extraction leaves around the words: the tatweel (an artefact of shaped vowel marks), control and format
     * characters, and runs of white space.
     */
    static String cleaned(String raw) {
        var out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == TATWEEL || Character.getType(c) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The text of each part of a section, without the heading: one part unless the section is too long. */
    private static List<String> bodies(Section section) {
        var body = String.join(" ", section.lines());
        long budget = TokenEstimate.weightOf(MAX_CHUNK_TOKENS);
        if (section.headingLine() != null) {
            budget -= TokenEstimate.weight(section.headingLine()) + TokenEstimate.weight("\n");
        }
        if (TokenEstimate.weight(body) <= budget) {
            return List.of(body);
        }
        return pack(pieces(body, budget), budget);
    }

    /** Sentences, and pieces of any sentence that is too long to be a part on its own. */
    private static List<Piece> pieces(String body, long budget) {
        var pieces = new ArrayList<Piece>();
        for (var sentence : Sentences.split(body)) {
            long weight = TokenEstimate.weight(sentence);
            if (weight <= budget) {
                pieces.add(new Piece(sentence, weight));
            } else {
                splitOversized(sentence, budget, pieces);
            }
        }
        return pieces;
    }

    /** Cuts a sentence between words, and a word without a break in it anywhere. */
    private static void splitOversized(String sentence, long budget, List<Piece> pieces) {
        var current = new StringBuilder();
        long currentWeight = 0;
        for (var word : sentence.split(" ")) {
            long weight = TokenEstimate.weight(word);
            if (weight > budget) {
                flush(current, currentWeight, pieces);
                current.setLength(0);
                currentWeight = 0;
                slices(word, budget, pieces);
                continue;
            }
            long added = current.isEmpty() ? weight : SPACE_WEIGHT + weight;
            if (currentWeight + added > budget) {
                flush(current, currentWeight, pieces);
                current.setLength(0);
                added = weight;
                currentWeight = 0;
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
            currentWeight += added;
        }
        flush(current, currentWeight, pieces);
    }

    private static void flush(StringBuilder text, long weight, List<Piece> pieces) {
        if (!text.isEmpty()) {
            pieces.add(new Piece(text.toString(), weight));
        }
    }

    private static void slices(String word, long budget, List<Piece> pieces) {
        int maxChars = (int) Math.max(1, budget / TokenEstimate.MAX_CHARACTER_WEIGHT);
        for (int start = 0; start < word.length(); ) {
            int end = Math.min(word.length(), start + maxChars);
            if (end < word.length() && Character.isLowSurrogate(word.charAt(end))) {
                end--;
            }
            var slice = word.substring(start, end);
            pieces.add(new Piece(slice, TokenEstimate.weight(slice)));
            start = end;
        }
    }

    /** Packs pieces into parts of at most {@code budget}, each part starting with the end of the one before. */
    private static List<String> pack(List<Piece> pieces, long budget) {
        long overlapBudget = TokenEstimate.weightOf(OVERLAP_TOKENS);
        var parts = new ArrayList<String>();
        int start = 0;
        while (start < pieces.size()) {
            int end = start;
            long weight = 0;
            while (end < pieces.size()
                    && (end == start || weight + SPACE_WEIGHT + pieces.get(end).weight() <= budget)) {
                weight += (end == start ? 0 : SPACE_WEIGHT) + pieces.get(end).weight();
                end++;
            }
            parts.add(join(pieces, start, end));
            if (end == pieces.size()) {
                break;
            }
            int next = end;
            long overlap = 0;
            while (next - 1 > start && overlap + SPACE_WEIGHT + pieces.get(next - 1).weight() <= overlapBudget) {
                next--;
                overlap += SPACE_WEIGHT + pieces.get(next).weight();
            }
            // The overlap must leave room for at least one piece that is new, or the parts would never advance.
            start = overlap + SPACE_WEIGHT + pieces.get(end).weight() > budget ? end : next;
        }
        return parts;
    }

    private static String join(List<Piece> pieces, int start, int end) {
        var text = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                text.append(' ');
            }
            text.append(pieces.get(i).text());
        }
        return text.toString();
    }

    /** a, b, ... z, aa, ab, ...: a part letter that never runs out. */
    static String letters(int index) {
        var letters = new StringBuilder();
        for (int n = index + 1; n > 0; n /= 26) {
            n--;
            letters.insert(0, (char) ('a' + n % 26));
        }
        return letters.toString();
    }
}
