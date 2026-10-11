package dev.proppilot.rag.chunk;

import dev.proppilot.rag.CitationRefs;
import dev.proppilot.text.Folding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cuts the extracted text of a lease document into the chunks that are searched and cited: a preamble ({@code #0}) and
 * one chunk for each numbered clause ({@code #7}). A clause of more than {@link #MAX_CHUNK_TOKENS} estimated tokens is
 * split on sentence boundaries into parts ({@code #7a}, {@code #7b}, ...), each starting with the last sentences of the
 * part before it, about {@link #OVERLAP_TOKENS} tokens of them, so an answer that straddles a cut is whole in one part.
 * When the last sentence of a part is longer than that by itself, the part after it starts with its last words.
 *
 * <p>Every chunk begins with its clause heading, so a part reads on its own. The page footers and the signature block
 * are not part of any chunk. Clauses are numbered in sequence: a heading starts a clause only when its number is the
 * one after the last clause's, so a paragraph that happens to start with a number cannot make a clause of its own or
 * throw the real clauses after it out of step. A heading may skip one or two numbers when the heading after it is
 * numbered right after it, so a clause whose heading extraction mangled does not take the clauses after it with it.
 *
 * <p>The text may be of any size and shape. Nothing here recurses or runs a regular expression over more than a
 * heading's length of text; the work is linear in the size of the text.
 */
@Component
public class LeaseChunker {

    public static final int MAX_CHUNK_TOKENS = 500;
    public static final int OVERLAP_TOKENS = 50;

    private static final int PREAMBLE = 0;
    /** How many clause numbers a heading may skip when the heading after it is numbered right. */
    private static final int MAX_SKIPPED_CLAUSES = 2;
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
                var ref = bodies.size() > 1
                        ? CitationRefs.format(documentCode, section.number(), part)
                        : CitationRefs.format(documentCode, section.number());
                chunks.add(new LeaseChunk(ref, chunks.size(),
                        section.number() == PREAMBLE ? null : section.number(), section.title(), section.page(),
                        content, Folding.fold(Folding.withoutDiacritics(content)), TokenEstimate.of(content)));
            }
        }
        return chunks;
    }

    /** A line of content, and the heading it reads as; null when it is not a heading. */
    private record Line(String text, int page, ClauseHeading.Heading heading) {
    }

    /** The preamble has no heading line and no title, and is number 0. */
    private record Section(int number, String title, String headingLine, int page, List<String> lines) {
    }

    private record Piece(String text, long weight) {
    }

    private static List<Section> sections(List<String> pages) {
        var lines = lines(pages);
        var sections = new ArrayList<Section>();
        Section current = null;
        int lastNumber = PREAMBLE;
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.heading() != null && isNextClause(lines, index, lastNumber)) {
                lastNumber = line.heading().number();
                current = new Section(lastNumber, line.heading().title(), line.text(), line.page(), new ArrayList<>());
                sections.add(current);
            } else {
                if (current == null) {
                    current = new Section(PREAMBLE, null, null, line.page(), new ArrayList<>());
                    sections.add(current);
                }
                current.lines().add(line.text());
            }
        }
        return sections;
    }

    /** The lines of the clauses and the preamble: no page footers, and nothing from the signature block on. */
    private static List<Line> lines(List<String> pages) {
        var lines = new ArrayList<Line>();
        for (int index = 0; index < pages.size(); index++) {
            for (var rawLine : pages.get(index).split("\n")) {
                var text = cleaned(rawLine);
                if (text.isEmpty() || PageFurniture.isPageFooter(text)) {
                    continue;
                }
                if (PageFurniture.isSignaturesHeading(text)) {
                    return lines;
                }
                lines.add(new Line(text, index + 1, ClauseHeading.parse(text).orElse(null)));
            }
        }
        return lines;
    }

    /**
     * Whether the heading at {@code index} numbers the clause that comes after the last one. A heading may skip up to
     * {@link #MAX_SKIPPED_CLAUSES} numbers when the next heading in the document is numbered right after it.
     */
    private static boolean isNextClause(List<Line> lines, int index, int lastNumber) {
        int number = lines.get(index).heading().number();
        if (number == lastNumber + 1) {
            return true;
        }
        if (number <= lastNumber || number > lastNumber + 1 + MAX_SKIPPED_CLAUSES) {
            return false;
        }
        for (int next = index + 1; next < lines.size(); next++) {
            if (lines.get(next).heading() != null) {
                return lines.get(next).heading().number() == number + 1;
            }
        }
        return false;
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

    /**
     * Packs pieces into parts of at most {@code budget}. Each part starts with the end of the one before it: the last
     * pieces that fit the overlap, or the last words of the last piece when that piece alone is longer than that.
     */
    private static List<String> pack(List<Piece> pieces, long budget) {
        long overlapBudget = TokenEstimate.weightOf(OVERLAP_TOKENS);
        var parts = new ArrayList<String>();
        var carried = "";
        int start = 0;
        while (start < pieces.size()) {
            var part = new StringBuilder(carried);
            long weight = TokenEstimate.weight(carried);
            int end = start;
            while (end < pieces.size()
                    && (end == start || weight + SPACE_WEIGHT + pieces.get(end).weight() <= budget)) {
                if (!part.isEmpty()) {
                    part.append(' ');
                    weight += SPACE_WEIGHT;
                }
                part.append(pieces.get(end).text());
                weight += pieces.get(end).weight();
                end++;
            }
            parts.add(part.toString());
            if (end < pieces.size()) {
                // The overlap must leave room for the piece that comes next, or the parts would never advance.
                long room = budget - SPACE_WEIGHT - pieces.get(end).weight();
                carried = tail(pieces, start, end, Math.min(overlapBudget, room));
            }
            start = end;
        }
        return parts;
    }

    /** The end of {@code pieces[start, end)} that weighs at most {@code limit}, cut at a word when no piece fits. */
    private static String tail(List<Piece> pieces, int start, int end, long limit) {
        int first = firstWithin(pieces, start, end, limit);
        if (first < end) {
            return join(pieces, first, end);
        }
        var words = words(pieces.get(end - 1).text());
        return join(words, firstWithin(words, 0, words.size(), limit), words.size());
    }

    /** The lowest index at which the pieces up to {@code end} weigh at most {@code limit}; {@code end} if none do. */
    private static int firstWithin(List<Piece> pieces, int start, int end, long limit) {
        int first = end;
        long weight = 0;
        while (first > start) {
            long added = pieces.get(first - 1).weight() + (first == end ? 0 : SPACE_WEIGHT);
            if (weight + added > limit) {
                break;
            }
            weight += added;
            first--;
        }
        return first;
    }

    private static List<Piece> words(String text) {
        var words = new ArrayList<Piece>();
        for (var word : text.split(" ")) {
            words.add(new Piece(word, TokenEstimate.weight(word)));
        }
        return words;
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
}
