package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.domain.Lease;
import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.agent.tools.MessageDrafter.Drafted;
import dev.proppilot.agent.tools.MessageDrafter.Language;
import dev.proppilot.agent.tools.MessageDrafter.Purpose;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tenant 7 owes rent and prefers English; tenant 1 is seeded with Arabic as the preferred language. */
class MessageDrafterIT extends PostgresIntegrationTest {

    @Autowired
    MessageDrafter drafter;
    @Autowired
    LeaseRepository leases;
    @Autowired
    JdbcTemplate jdbc;

    private Lease leaseOf(long tenantId) {
        var unit = jdbc.queryForObject("select u.code from leases l join units u on u.id = l.unit_id where l.tenant_id = ?",
                String.class, tenantId);
        return leases.findActiveByUnitCode(unit).orElseThrow();
    }

    @Test
    void usesTheTenantsPreferredLanguageUnlessAskedOtherwise() {
        var arabicSpeaker = leaseOf(1);
        assertThat(arabicSpeaker.getTenant().getPreferredLanguage()).isEqualTo("ar");

        var preferred = (Drafted.Message) drafter.draft(arabicSpeaker, Purpose.LEASE_RENEWAL, Optional.empty());
        var asked = (Drafted.Message) drafter.draft(arabicSpeaker, Purpose.LEASE_RENEWAL, Optional.of(Language.EN));

        assertThat(preferred.language()).isEqualTo(Language.AR);
        assertThat(preferred.subject()).contains("تجديد");
        assertThat(asked.language()).isEqualTo(Language.EN);
        assertThat(asked.subject()).containsIgnoringCase("renewal");
    }

    @Test
    void aRentReminderQuotesTheOverdueMonthsAndNamesTheTenantInBothLanguages() {
        var drafted = (Drafted.Message) drafter.draft(leaseOf(7), Purpose.RENT_REMINDER, Optional.of(Language.EN));

        assertThat(drafted.tenantEn()).isEqualTo("Ahmed Al-Harbi");
        assertThat(drafted.tenantAr()).isEqualTo("أحمد الحربي");
        assertThat(drafted.body()).contains("Dear Ahmed Al-Harbi").contains("SAR");
    }

    @Test
    void refusesARentReminderForATenantWithNothingOverdue() {
        var paidUp = jdbc.queryForObject("""
                select l.tenant_id from leases l
                where not exists (select 1 from payments p where p.lease_id = l.id and p.paid_on is null)
                limit 1""", Long.class);

        var drafted = drafter.draft(leaseOf(paidUp), Purpose.RENT_REMINDER, Optional.empty());

        assertThat(drafted).isInstanceOfSatisfying(Drafted.NoOverdueRent.class, none ->
                assertThat(none.message()).contains("has no overdue rent"));
    }
}
