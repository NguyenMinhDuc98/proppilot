package dev.proppilot.rag.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Writes text down A4 pages with the bundled IBM Plex Sans Arabic, which has both the Latin and the Arabic letters. The
 * canvas wraps lines itself, starts a new page when one is full and numbers the pages in the footer. Lines start at
 * the left margin, or at the right margin for right-to-left text.
 */
final class PdfCanvas implements AutoCloseable {

    enum Weight { REGULAR, BOLD }

    enum Align { START, CENTER }

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 56f;
    private static final float BODY_BOTTOM = 72f;
    private static final float FOOTER_BASELINE = 36f;
    private static final float FOOTER_SIZE = 9f;
    /** The distance between two lines of text, in multiples of the font size. */
    private static final float LEADING = 1.5f;

    private final PDDocument document = new PDDocument();
    private final PDFont regular;
    private final PDFont bold;
    private final boolean rightToLeft;
    private final List<PDPage> pages = new ArrayList<>();
    private PDPageContentStream stream;
    private float y;

    PdfCanvas(boolean rightToLeft) throws IOException {
        this.rightToLeft = rightToLeft;
        this.regular = loadFont("IBMPlexSansArabic-Regular.ttf");
        this.bold = loadFont("IBMPlexSansArabic-Bold.ttf");
        startPage();
    }

    static float leading(float fontSize) {
        return fontSize * LEADING;
    }

    /** Writes the text as one or more lines, a new page first when the lines do not fit. */
    void text(String text, float fontSize, Weight weight, Align align) throws IOException {
        var font = font(weight);
        for (var line : wrap(text, font, fontSize)) {
            makeRoomFor(leading(fontSize));
            y -= leading(fontSize);
            draw(stream, line, font, fontSize, align, y);
        }
    }

    void space(float points) {
        y -= points;
    }

    /** Starts a new page unless this much height is left on the current one, so a heading never ends a page alone. */
    void makeRoomFor(float points) throws IOException {
        if (y - points < BODY_BOTTOM) {
            startPage();
        }
    }

    void rule() throws IOException {
        y -= 4;
        stream.moveTo(MARGIN, y);
        stream.lineTo(PAGE.getWidth() - MARGIN, y);
        stream.stroke();
    }

    /** Adds the page numbers and returns the PDF. */
    byte[] finish(String footerFormat, String title, long documentId) throws IOException {
        stream.close();
        stream = null;
        for (int number = 1; number <= pages.size(); number++) {
            var footer = RootFormat.format(footerFormat, number, pages.size());
            try (var footerStream = new PDPageContentStream(document, pages.get(number - 1),
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                draw(footerStream, footer, regular, FOOTER_SIZE, Align.CENTER, FOOTER_BASELINE);
            }
        }
        document.getDocumentInformation().setTitle(title);
        // Without an id PDFBox derives one from the clock, and the same lease would give different bytes every time.
        document.setDocumentId(documentId);
        var out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        if (stream != null) {
            stream.close();
        }
        document.close();
    }

    private void startPage() throws IOException {
        if (stream != null) {
            stream.close();
        }
        var page = new PDPage(PAGE);
        document.addPage(page);
        pages.add(page);
        stream = new PDPageContentStream(document, page);
        y = PAGE.getHeight() - MARGIN;
    }

    private PDFont loadFont(String file) throws IOException {
        try (InputStream in = PdfCanvas.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) {
                throw new IOException("Font not on the class path: " + file);
            }
            return PDType0Font.load(document, in);
        }
    }

    private PDFont font(Weight weight) {
        return weight == Weight.BOLD ? bold : regular;
    }

    private List<String> wrap(String text, PDFont font, float fontSize) throws IOException {
        float available = PAGE.getWidth() - 2 * MARGIN;
        var lines = new ArrayList<String>();
        var line = "";
        for (var word : text.split(" ")) {
            var candidate = line.isEmpty() ? word : line + " " + word;
            if (line.isEmpty() || width(candidate, font, fontSize) <= available) {
                line = candidate;
            } else {
                lines.add(line);
                line = word;
            }
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    private float width(String logicalLine, PDFont font, float fontSize) throws IOException {
        return font.getStringWidth(shown(logicalLine)) / 1000f * fontSize;
    }

    private String shown(String logicalLine) {
        return rightToLeft ? RightToLeftText.visual(logicalLine) : logicalLine;
    }

    private void draw(PDPageContentStream target, String logicalLine, PDFont font, float fontSize, Align align,
                      float baseline) throws IOException {
        var shown = shown(logicalLine);
        float width = font.getStringWidth(shown) / 1000f * fontSize;
        float x = switch (align) {
            case CENTER -> (PAGE.getWidth() - width) / 2;
            case START -> rightToLeft ? PAGE.getWidth() - MARGIN - width : MARGIN;
        };
        target.beginText();
        target.setFont(font, fontSize);
        target.newLineAtOffset(x, baseline);
        target.showText(shown);
        target.endText();
    }
}
