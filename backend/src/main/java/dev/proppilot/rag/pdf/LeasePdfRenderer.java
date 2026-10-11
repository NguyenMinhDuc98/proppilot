package dev.proppilot.rag.pdf;

import dev.proppilot.rag.pdf.PdfCanvas.Align;
import dev.proppilot.rag.pdf.PdfCanvas.Weight;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Lays a {@link LeaseText} out on A4 pages. */
final class LeasePdfRenderer {

    private static final float TITLE_SIZE = 17f;
    private static final float HEADING_SIZE = 12f;
    private static final float BODY_SIZE = 11f;
    private static final float BEFORE_SECTION = 12f;
    private static final float BEFORE_CLAUSE = 14f;
    private static final float BETWEEN_PARAGRAPHS = 4f;
    /** A heading is never left alone at the bottom of a page: this many lines of the clause go with it. */
    private static final int LINES_KEPT_WITH_HEADING = 3;

    byte[] render(LeaseText text, long documentId) {
        try (var canvas = new PdfCanvas(text.language().rightToLeft())) {
            titleBlock(canvas, text);
            for (var section : text.preamble()) {
                heading(canvas, section.heading(), BEFORE_SECTION);
                for (var line : section.lines()) {
                    canvas.text(line, BODY_SIZE, Weight.REGULAR, Align.START);
                }
            }
            for (var clause : text.clauses()) {
                heading(canvas, text.heading(clause), BEFORE_CLAUSE);
                for (var paragraph : clause.paragraphs()) {
                    canvas.text(paragraph, BODY_SIZE, Weight.REGULAR, Align.START);
                    canvas.space(BETWEEN_PARAGRAPHS);
                }
            }
            signatures(canvas, text);
            return canvas.finish(text.pageFooterFormat(), text.title(), documentId);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the lease document", e);
        }
    }

    private static void titleBlock(PdfCanvas canvas, LeaseText text) throws IOException {
        canvas.text(text.title(), TITLE_SIZE, Weight.BOLD, Align.CENTER);
        canvas.text(text.reference(), BODY_SIZE, Weight.REGULAR, Align.CENTER);
        canvas.rule();
    }

    private static void heading(PdfCanvas canvas, String heading, float spaceBefore) throws IOException {
        canvas.space(spaceBefore);
        canvas.makeRoomFor(PdfCanvas.leading(HEADING_SIZE) + LINES_KEPT_WITH_HEADING * PdfCanvas.leading(BODY_SIZE));
        canvas.text(heading, HEADING_SIZE, Weight.BOLD, Align.START);
    }

    private static void signatures(PdfCanvas canvas, LeaseText text) throws IOException {
        canvas.space(BEFORE_CLAUSE + BEFORE_SECTION);
        float block = PdfCanvas.leading(HEADING_SIZE)
                + (text.signatureLines().size() + 1) * PdfCanvas.leading(BODY_SIZE);
        canvas.makeRoomFor(block);
        canvas.text(text.signaturesHeading(), HEADING_SIZE, Weight.BOLD, Align.START);
        for (var line : text.signatureLines()) {
            canvas.text(line, BODY_SIZE, Weight.REGULAR, Align.START);
            canvas.space(BETWEEN_PARAGRAPHS);
        }
    }
}
