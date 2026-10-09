package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A unit that changed hands: one tenant holds the active lease (Aug to Oct, all paid), another held an earlier,
 * inactive one (May to Jul, all unpaid). The data is rolled back after every test.
 */
@Transactional
class PaymentHistoryScopeIT extends PostgresIntegrationTest {

    private static final String UNIT = "F-901";

    @Autowired
    ToolRegistry registry;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void unitWithAnActiveAndAnEarlierLease() {
        long unitId = jdbc.queryForObject("""
                insert into units (building_id, code, floor, bedrooms, area_sqm, monthly_rent, status)
                select id, ?, 9, 2, 100, 5000, 'OCCUPIED' from buildings where code = 'F'
                returning id""", Long.class, UNIT);
        long earlier = insertLease(unitId, insertTenant("Previous"), false, LocalDate.of(2026, 5, 1));
        long current = insertLease(unitId, insertTenant("Current"), true, LocalDate.of(2026, 8, 1));
        for (int month = 5; month <= 7; month++) {
            insertPayment(earlier, LocalDate.of(2026, month, 1), false);
        }
        for (int month = 8; month <= 10; month++) {
            insertPayment(current, LocalDate.of(2026, month, 1), true);
        }
    }

    private long insertTenant(String name) {
        return jdbc.queryForObject("""
                insert into tenants (name_en, name_ar, phone, email, preferred_language)
                values (?, ?, '+966500000000', 'test@example.com', 'en') returning id""", Long.class,
                "Qwerty " + name, "كويرتي " + name);
    }

    private long insertLease(long unitId, long tenantId, boolean active, LocalDate start) {
        return jdbc.queryForObject("""
                insert into leases (unit_id, tenant_id, start_date, end_date, monthly_rent, active)
                values (?, ?, ?, ?, 5000, ?) returning id""", Long.class, unitId, tenantId, start, start.plusMonths(3), active);
    }

    private void insertPayment(long leaseId, LocalDate period, boolean paid) {
        jdbc.update("insert into payments (lease_id, period, due_date, amount, paid_amount, paid_on) values (?, ?, ?, 5000, ?, ?)",
                leaseId, period, period, paid ? 5000 : 0, paid ? period : null);
    }

    private JsonNode history(String args) throws Exception {
        var result = registry.execute("get_payment_history", json.readTree(args));
        assertThat(result.error()).as(result.content()).isFalse();
        return json.readTree(result.content());
    }

    @Test
    void showsOnlyTheCurrentLeaseByDefault() throws Exception {
        var d = history("{\"unit_code\":\"" + UNIT + "\"}");

        assertThat(d.get("payments").findValuesAsText("month")).containsExactly("2026-10", "2026-09", "2026-08");
        assertThat(d.get("unpaidMonths").asInt()).isZero();
        assertThat(d.get("allLeases").asBoolean()).isFalse();
    }

    @Test
    void showsOnlyTheCurrentLeaseWhenAllLeasesIsFalse() throws Exception {
        var d = history("{\"unit_code\":\"" + UNIT + "\",\"all_leases\":false}");

        assertThat(d.get("payments")).hasSize(3);
    }

    @Test
    void includesPreviousLeasesWhenAskedTo() throws Exception {
        var d = history("{\"unit_code\":\"" + UNIT + "\",\"all_leases\":true}");

        assertThat(d.get("payments").findValuesAsText("month"))
                .containsExactly("2026-10", "2026-09", "2026-08", "2026-07", "2026-06", "2026-05");
        assertThat(d.get("unpaidMonths").asInt()).isEqualTo(3);
        assertThat(d.get("allLeases").asBoolean()).isTrue();
    }

    @Test
    void theMonthsLimitAppliesToTheWholeHistory() throws Exception {
        var d = history("{\"unit_code\":\"" + UNIT + "\",\"all_leases\":true,\"months\":4}");

        assertThat(d.get("payments").findValuesAsText("month")).containsExactly("2026-10", "2026-09", "2026-08", "2026-07");
    }

    @Test
    void scopesALookupByTenantNameToTheirActiveLease() throws Exception {
        var byName = history("{\"tenant_name\":\"Qwerty Current\"}");
        var everything = history("{\"tenant_name\":\"Qwerty Current\",\"all_leases\":true}");

        assertThat(byName.get("payments")).hasSize(3);
        assertThat(everything.get("payments")).hasSize(6);
    }

    @Test
    void aTenantWhoseLeaseEndedHasNoCurrentHistory() throws Exception {
        var result = registry.execute("get_payment_history", json.readTree("{\"tenant_name\":\"Qwerty Previous\"}"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("no active lease");
    }

    @Test
    void rejectsANonBooleanAllLeases() throws Exception {
        var result = registry.execute("get_payment_history",
                json.readTree("{\"unit_code\":\"" + UNIT + "\",\"all_leases\":\"yes\"}"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("all_leases must be a boolean");
    }
}
