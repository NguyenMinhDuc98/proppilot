package dev.proppilot.rag.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Reads the text of a PDF a page at a time. Arabic comes back in logical order with its letters unjoined, so it can be
 * searched. Latin text in a right-to-left line may come back away from where it was written, a unit code at the end of
 * the line at the start of it for example, and two Latin runs in one line may come back in each other's place. A vowel
 * mark may come back with a tatweel in front of it. What reads this text must allow for all of it; a document that is
 * written to be read back keeps one Latin run to a line.
 */
@Component
public class PdfTextExtractor {

    /** The text of each page, in page order; lines are separated by {@code \n}. */
    public List<String> pages(byte[] pdf) {
        try (var document = Loader.loadPDF(pdf)) {
            var stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            var pages = new ArrayList<String>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the PDF", e);
        }
    }
}
