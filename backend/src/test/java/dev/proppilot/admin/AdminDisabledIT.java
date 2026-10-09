package dev.proppilot.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Without ADMIN_TOKEN the admin endpoints must look like they do not exist, whatever the caller sends. */
@AutoConfigureMockMvc
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
}
