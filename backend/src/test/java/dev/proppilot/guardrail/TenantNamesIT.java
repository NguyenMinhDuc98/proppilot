package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.admin.DemoResetService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The names the guardrail looks for are the seeded tenants', read from the table, and read again by a reset. */
class TenantNamesIT extends PostgresIntegrationTest {

    private static final long NEWCOMER = 900_001L;

    @Autowired
    Guardrail guardrail;
    @Autowired
    TenantNames tenantNames;
    @Autowired
    DemoResetService demo;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeTheNewcomer() {
        jdbc.update("delete from tenants where id = ?", NEWCOMER);
        tenantNames.refresh();
    }

    private boolean flags(String answer) {
        var evidence = new Evidence(List.of(), List.of("Who is late?"), Set.of());
        return !guardrail.check(answer, evidence).orElseThrow().isEmpty();
    }

    @Test
    void aSeededTenantInEitherLanguageIsKnown() {
        assertThat(flags("Ahmed Al-Harbi is late.")).isTrue();
        assertThat(flags("المستأجر أحمد الحربي متأخر")).isTrue();
        assertThat(flags("Nobody Al-Nowhere is late.")).isFalse();
    }

    @Test
    void aResetReadsTheNamesAgain() {
        assertThat(flags("Zaid Al-Newcomer is late.")).isFalse();
        jdbc.update("""
                insert into tenants (id, name_en, name_ar, phone, email, preferred_language)
                values (?, 'Zaid Al-Newcomer', 'زيد القادم', '+966500000000', 'zaid@example.test', 'en')""", NEWCOMER);
        assertThat(flags("Zaid Al-Newcomer is late.")).as("the roster is read once").isFalse();

        demo.reset();

        assertThat(flags("Zaid Al-Newcomer is late.")).isTrue();
    }
}
