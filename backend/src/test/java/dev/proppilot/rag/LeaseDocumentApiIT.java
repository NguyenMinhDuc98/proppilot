package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class LeaseDocumentApiIT extends CorpusIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsTheDocumentsWithTheirUnitTenantAndSize() throws Exception {
        var first = jdbc.queryForMap("""
                select d.code, d.title, d.language, u.code as unit_code, t.name_en, t.name_ar, d.page_count,
                       (select count(*) from lease_chunks c where c.document_id = d.id) as chunks
                from lease_documents d join leases l on l.id = d.lease_id join units u on u.id = l.unit_id
                join tenants t on t.id = l.tenant_id order by d.lease_id limit 1""");

        mvc.perform(get("/api/lease-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)))
                .andExpect(jsonPath("$[0].code").value(first.get("code")))
                .andExpect(jsonPath("$[0].title").value(first.get("title")))
                .andExpect(jsonPath("$[0].language").value(first.get("language")))
                .andExpect(jsonPath("$[0].unitCode").value(first.get("unit_code")))
                .andExpect(jsonPath("$[0].tenantNameEn").value(first.get("name_en")))
                .andExpect(jsonPath("$[0].tenantNameAr").value(first.get("name_ar")))
                .andExpect(jsonPath("$[0].pageCount").value(first.get("page_count")))
                .andExpect(jsonPath("$[0].chunkCount").value(((Number) first.get("chunks")).intValue()));
    }

    @Test
    void listsOnlyWhatTheEndpointPromisesAndNotThePdfBytes() throws Exception {
        var body = mvc.perform(get("/api/lease-documents")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("pdf\":", "%PDF");
        mvc.perform(get("/api/lease-documents"))
                .andExpect(jsonPath("$[0].*", hasSize(8)));
    }

    @Test
    void servesAPdfInlineThatTheBrowserMayCacheForAnHour() throws Exception {
        var code = jdbc.queryForObject("select code from lease_documents order by lease_id limit 1", String.class);
        var stored = jdbc.queryForObject("select pdf from lease_documents where code = ?", byte[].class, code);

        var response = mvc.perform(get("/api/lease-documents/" + code + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + code + ".pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                .andReturn().getResponse();

        var bytes = response.getContentAsByteArray();
        assertThat(new String(bytes, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThat(bytes).isEqualTo(stored);
    }

    @Test
    void servesTheDocumentsOfBothLanguages() throws Exception {
        for (var language : new String[] {"en", "ar"}) {
            var code = jdbc.queryForObject("select code from lease_documents where language = ? limit 1",
                    String.class, language);

            mvc.perform(get("/api/lease-documents/" + code + "/pdf"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE));
        }
    }

    @Test
    void aCodeThatHasNoDocumentIs404() throws Exception {
        mvc.perform(get("/api/lease-documents/LSE-Z-999/pdf")).andExpect(status().isNotFound());
        // A lease that exists but is not among the first thirty has no document.
        var outside = jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id
                where l.active order by l.id desc limit 1""", String.class);
        mvc.perform(get("/api/lease-documents/LSE-" + outside + "/pdf")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lse-a-504", "LSE-A-504%3Bdrop", "A-504", "LSE-A-", "LSE--504", "LSE-A-504X",
            "LSE-AAAAAAAAA-504", "LSE-A-504504504", "%2E%2E", "LSE-A-504%00"})
    void aCodeThatDoesNotLookLikeADocumentCodeIs400(String code) throws Exception {
        mvc.perform(get("/api/lease-documents/" + code + "/pdf")).andExpect(status().isBadRequest());
    }
}
