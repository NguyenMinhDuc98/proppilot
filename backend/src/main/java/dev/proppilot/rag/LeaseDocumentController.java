package dev.proppilot.rag;

import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The lease documents: public, because all of them are generated from fake demo data. */
@RestController
@RequestMapping("/api/lease-documents")
@Validated
public class LeaseDocumentController {

    private final LeaseDocumentCatalog catalog;

    public LeaseDocumentController(LeaseDocumentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<LeaseDocumentView> list() {
        return catalog.list();
    }

    @GetMapping("/{code}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable @Pattern(regexp = CitationRefs.DOCUMENT_CODE) String code) {
        return catalog.pdf(code)
                .map(pdf -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.inline().filename(code + ".pdf").build().toString())
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                        .body(pdf))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
