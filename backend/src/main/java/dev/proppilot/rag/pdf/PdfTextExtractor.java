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
 * searched. A Latin token at the end of a right-to-left line, such as a unit code, may come back at the start of the
 * line, and a vowel mark may come back with a tatweel in front of it; what reads this text must allow for both.
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
