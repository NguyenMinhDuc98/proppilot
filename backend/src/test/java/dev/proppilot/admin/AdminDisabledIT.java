package dev.proppilot.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Without ADMIN_TOKEN the admin endpoints must look like they do not exist, whatever the caller sends. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@SpringBootTest(properties = "proppilot.admin.token=")
class AdminDisabledIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void answersNotFoundWithAndWithoutAHeader() throws Exception {
        mvc.perform(get("/api/admin/runs")).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/runs").header("X-Admin-Token", "any-token-any-token-any-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRunDetailDoesNotExistEither() throws Exception {
        var id = "00000000-0000-0000-0000-000000000001";

        mvc.perform(get("/api/admin/runs/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/runs/" + id).header("X-Admin-Token", "any-token-any-token-any-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theDemoResetDoesNotExistEither() throws Exception {
        mvc.perform(post("/api/admin/reset")).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/reset").header("X-Admin-Token", "any-token-any-token-any-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theLeaseCorpusReindexDoesNotExistEither() throws Exception {
        mvc.perform(post("/api/admin/rag/reindex")).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/rag/reindex").header("X-Admin-Token", "any-token-any-token-any-token"))
                .andExpect(status().isNotFound());
    }
}
