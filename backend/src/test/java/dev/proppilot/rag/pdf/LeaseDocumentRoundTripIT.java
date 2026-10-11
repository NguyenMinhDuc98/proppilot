package dev.proppilot.rag.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.agent.MoneyText;
import dev.proppilot.rag.CorpusIntegrationTest;
import dev.proppilot.rag.LeaseDocumentSeeder;
import dev.proppilot.rag.LeaseFacts;
import dev.proppilot.rag.LeaseFactsRepository;
import dev.proppilot.rag.LeaseTerms;
import dev.proppilot.text.Folding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real PDFs from the seeded database, in both languages, read back through PDFBox and cut into chunks: what the
 * template wrote is what the corpus holds, with the right number, title and page for every clause.
 */
class LeaseDocumentRoundTripIT extends CorpusIntegrationTest {

    @Autowired
    LeaseFactsRepository leases;
    @Autowired
    PdfTextExtractor extractor;

    @Test
    void everyClauseOfEveryDocumentIsStoredWithItsNumberTitleAndPage() {
        var all = leases.firstActive(30);
        int documents = 0;
        for (int index = 0; index < all.size(); index++) {
            var facts = all.get(index);
            var written = written(facts, index + 1);
            var pages = extractor.pages(jdbc.queryForObject(
                    "select pdf from lease_documents where code = ?", byte[].class, facts.documentCode()));
            var chunks = jdbc.queryForList("""
                    select c.ref, c.section_no, c.section_title, c.page, c.content
                    from lease_chunks c join lease_documents d on d.id = c.document_id
                    where d.code = ? order by c.ordinal""", facts.documentCode());

            assertThat(chunks.get(0).get("ref")).as(facts.documentCode()).isEqualTo(facts.documentCode() + "#0");
            assertThat(chunks.get(0).get("section_no")).isNull();
            assertThat(chunks.get(0).get("page")).isEqualTo(1);
            for (var clause : written.clauses()) {
                var first = firstChunkOf(chunks, clause.number());
                var heading = written.heading(clause);

                assertThat(first.get("section_title")).as("%s clause %d", facts.documentCode(), clause.number())
                        .isEqualTo(clause.title());
                assertThat((String) first.get("content")).startsWith(heading);
                assertThat(first.get("page")).as("page of %s in %s", heading, facts.documentCode())
                        .isEqualTo(firstPageWithLine(pages, heading));
            }
            assertThat(chunks.stream().map(row -> row.get("section_no")).filter(java.util.Objects::nonNull).distinct()
                    .count()).isEqualTo(written.clauses().size());
            documents++;
        }
        assertThat(documents).isEqualTo(30);
    }

    @Test
    void theRentAndTheUnitCodeAreRecoverableAfterFoldingInEnglishAndInArabic() {
        var checked = new java.util.HashSet<String>();
        for (var facts : leases.firstActive(30)) {
            var rentClause = jdbc.queryForObject("select search_text from lease_chunks where ref = ?", String.class,
                    facts.documentCode() + "#1");
            var preamble = jdbc.queryForObject("select search_text from lease_chunks where ref = ?", String.class,
                    facts.documentCode() + "#0");

            assertThat(rentClause).as(facts.documentCode()).contains(Folding.fold(MoneyText.of(facts.monthlyRent())));
            assertThat(preamble).contains(Folding.fold(facts.unitCode()), Folding.fold(facts.tenantName()));
            checked.add(facts.language().code());
        }
        assertThat(checked).containsExactlyInAnyOrder("en", "ar");
    }

    private static LeaseText written(LeaseFacts facts, int position) {
        var terms = LeaseTerms.of(facts.leaseId());
        if (position == LeaseDocumentSeeder.PLANTED_INSTRUCTION_POSITION) {
            terms = terms.withPlantedInstruction();
        }
        var template = facts.language().rightToLeft() ? new ArabicLeaseTemplate() : new EnglishLeaseTemplate();
        return template.write(facts, terms);
    }

    private static Map<String, Object> firstChunkOf(List<Map<String, Object>> chunks, int clauseNumber) {
        return chunks.stream().filter(row -> Integer.valueOf(clauseNumber).equals(row.get("section_no")))
                .findFirst().orElseThrow(() -> new AssertionError("No chunk for clause " + clauseNumber));
    }

    /** The page of the first line of the document that is exactly this heading, counting from 1. */
    private static int firstPageWithLine(List<String> pages, String heading) {
        for (int page = 0; page < pages.size(); page++) {
            if (pages.get(page).lines().anyMatch(heading::equals)) {
                return page + 1;
            }
        }
        throw new AssertionError("No page has the line: " + heading);
    }
}
