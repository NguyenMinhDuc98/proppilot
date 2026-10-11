package dev.proppilot.rag;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base for tests that read the stored lease corpus. The seeder is not started with the application here; every test
 * writes what is missing first, which costs nothing when the documents are already there. The database is shared by
 * the whole test run, so the first test to need the corpus is the one that pays for writing it.
 */
@SpringBootTest(properties = "proppilot.rag.seed-on-startup=false")
@AutoConfigureMockMvc
public abstract class CorpusIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    protected LeaseDocumentSeeder seeder;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void theCorpusIsWritten() {
        seeder.seed();
    }
}
