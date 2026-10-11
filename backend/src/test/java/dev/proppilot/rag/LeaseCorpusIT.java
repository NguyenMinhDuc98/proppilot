package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.rag.chunk.LeaseChunker;
import dev.proppilot.rag.chunk.TokenEstimate;
import dev.proppilot.rag.pdf.LeasePdfGenerator;
import dev.proppilot.rag.pdf.PdfTextExtractor;
import dev.proppilot.rag.pdf.PlantedInstruction;
import dev.proppilot.text.Folding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The corpus as the seeder writes it from the seeded database. */
class LeaseCorpusIT extends CorpusIntegrationTest {

    private static final Pattern REF = Pattern.compile("LSE-[A-Z]-\\d{3}#\\d{1,2}[a-z]{0,2}");

    @Autowired
    LeaseFactsRepository leases;
    @Autowired
    PdfTextExtractor extractor;

    @Test
    void theFirstThirtyActiveLeasesEachHaveOneDocumentInTheLanguageOfTheTenant() {
        var expected = leases.firstActive(30);

        var documents = jdbc.queryForList("""
                select d.lease_id, d.code, d.language, d.generator_version, u.code as unit_code
                from lease_documents d join leases l on l.id = d.lease_id join units u on u.id = l.unit_id
                order by d.lease_id""");

        assertThat(expected).hasSize(30);
        assertThat(documents).hasSize(30);
        for (int i = 0; i < 30; i++) {
            var row = documents.get(i);
            assertThat(row.get("lease_id")).isEqualTo(expected.get(i).leaseId());
            assertThat(row.get("code")).isEqualTo("LSE-" + row.get("unit_code"));
            assertThat(row.get("language")).isEqualTo(expected.get(i).language().code());
            assertThat(row.get("generator_version")).isEqualTo(LeasePdfGenerator.VERSION);
        }
        assertThat(documents.stream().map(row -> row.get("language")).distinct()).containsExactlyInAnyOrder("en", "ar");
    }

    @Test
    void everyStoredPdfIsAPdfOfAtLeastTwoPagesAndItsPageCountIsStored() {
        var documents = jdbc.queryForList("select code, pdf, page_count from lease_documents");

        assertThat(documents).hasSize(30).allSatisfy(row -> {
            var pdf = (byte[]) row.get("pdf");
            assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat((Integer) row.get("page_count")).isGreaterThanOrEqualTo(2);
            assertThat(extractor.pages(pdf)).hasSize((Integer) row.get("page_count"));
        });
    }

    @Test
    void chunksAreStoredWithoutEmbeddingsAndAreFoundByFullTextSearch() {
        assertThat(count("select count(*) from lease_chunks where embedding is not null "
                + "or embedding_model is not null")).isZero();
        assertThat(count("select count(*) from lease_chunks where fts = ''::tsvector")).isZero();
        assertThat(count("select count(*) from lease_chunks "
                + "where fts @@ plainto_tsquery('english', 'late payment fee')")).isPositive();
        assertThat(count("select count(*) from lease_chunks "
                + "where fts @@ plainto_tsquery('arabic', 'غرامه التاخير')")).isPositive();
    }

    @Test
    void everyRefIsUniqueAndSearchTextIsTheFoldedContent() {
        var chunks = jdbc.queryForList("select ref, content, search_text, token_estimate from lease_chunks");

        assertThat(chunks).isNotEmpty().allSatisfy(row -> {
            var content = (String) row.get("content");
            assertThat((String) row.get("ref")).matches(REF);
            assertThat(row.get("search_text")).isEqualTo(Folding.fold(Folding.withoutDiacritics(content)));
            assertThat(row.get("token_estimate")).isEqualTo(TokenEstimate.of(content));
            assertThat((Integer) row.get("token_estimate")).isLessThanOrEqualTo(LeaseChunker.MAX_CHUNK_TOKENS);
        });
        assertThat(chunks.stream().map(row -> row.get("ref")).distinct().count()).isEqualTo(chunks.size());
    }

    @Test
    void aLongAnnexIsSplitIntoPartsWithStableRefsAndOverlap() {
        var annexed = leases.firstActive(30).stream()
                .filter(facts -> LeaseTerms.of(facts.leaseId()).houseRulesAnnex()).toList();
        assertThat(annexed.stream().map(LeaseFacts::language).distinct())
                .as("both languages have a document with the annex")
                .containsExactlyInAnyOrder(DocumentLanguage.EN, DocumentLanguage.AR);

        for (var facts : annexed) {
            var parts = jdbc.queryForList("""
                    select c.ref, c.content, c.token_estimate from lease_chunks c
                    join lease_documents d on d.id = c.document_id
                    where d.code = ? and c.section_no = 13 order by c.ordinal""", facts.documentCode());

            assertThat(parts).as(facts.documentCode()).hasSizeBetween(2, 4);
            for (int part = 0; part < parts.size(); part++) {
                assertThat(parts.get(part).get("ref")).isEqualTo(facts.documentCode() + "#13" + (char) ('a' + part));
                assertThat(((String) parts.get(part).get("content")).lines().findFirst().orElseThrow())
                        .startsWith(facts.language().rightToLeft() ? "البند 13: " : "13. ");
                if (part > 0) {
                    var previous = (String) parts.get(part - 1).get("content");
                    var opening = body((String) parts.get(part).get("content")).substring(0, 25);
                    assertThat(previous).as("part %d starts with the end of part %d", part, part - 1)
                            .contains(opening);
                }
            }
            int tokens = parts.stream().mapToInt(row -> (Integer) row.get("token_estimate")).sum();
            assertThat(tokens).isGreaterThan(LeaseChunker.MAX_CHUNK_TOKENS);
        }
    }

    @Test
    void exactlyOneDocumentCarriesThePlantedInstructionAndItIsThe29thActiveLeaseById() {
        var english = PlantedInstruction.of(DocumentLanguage.EN);
        var arabic = PlantedInstruction.of(DocumentLanguage.AR);

        List<Map<String, Object>> carrying = jdbc.queryForList("""
                select d.lease_id, c.ref, c.section_title
                from lease_chunks c join lease_documents d on d.id = c.document_id
                where position(? in c.content) > 0 or position(? in c.content) > 0""", english, arabic);

        long twentyNinth = jdbc.queryForObject(
                "select id from leases where active order by id limit 1 offset 28", Long.class);
        assertThat(LeaseDocumentSeeder.PLANTED_INSTRUCTION_POSITION).isEqualTo(29);
        assertThat(carrying).hasSize(1);
        assertThat(carrying.get(0).get("lease_id")).isEqualTo(twentyNinth);
        assertThat(carrying.get(0).get("section_title")).isIn("Special conditions", "شروط خاصة");
    }

    private static String body(String content) {
        return content.substring(content.indexOf('\n') + 1);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
