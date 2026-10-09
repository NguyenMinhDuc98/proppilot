package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs every tool against the real seeded database. Tenant 7 is seeded as a "chronic" non-payer. */
class ToolsIT extends PostgresIntegrationTest {

    @Autowired
    ToolRegistry registry;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    private ToolResult call(String tool, String args) throws Exception {
        return registry.execute(tool, json.readTree(args));
    }

    private JsonNode data(ToolResult result) throws Exception {
        assertThat(result.error()).as(result.content()).isFalse();
        return json.readTree(result.content());
    }

    private String chronicUnit() {
        return jdbc.queryForObject("select u.code from leases l join units u on u.id = l.unit_id where l.tenant_id = 7", String.class);
    }

    @Test
    void registryExposesTheSixTools() {
        assertThat(registry.specs()).extracting("name").containsExactly(
                "draft_tenant_message", "find_overdue_tenants", "get_occupancy_summary",
                "get_payment_history", "get_unit_details", "search_units");
    }

    @Test
    void searchUnitsFiltersAndCapsTheList() throws Exception {
        var all = data(call("search_units", "{\"city\":\"Riyadh\",\"status\":\"VACANT\"}"));

        assertThat(all.get("total").asInt()).isPositive();
        assertThat(all.get("units")).allSatisfy(u -> {
            assertThat(u.get("cityEn").asText()).isEqualTo("Riyadh");
            assertThat(u.get("status").asText()).isEqualTo("VACANT");
        });

        var everything = data(call("search_units", "{}"));
        assertThat(everything.get("total").asInt()).isEqualTo(150);
        assertThat(everything.get("shown").asInt()).isEqualTo(15);
    }

    @Test
    void searchUnitsRejectsBadInputWithAHelpfulError() throws Exception {
        var result = call("search_units", "{\"status\":\"BROKEN\"}");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("VACANT");
        assertThat(call("search_units", "{\"bedrooms\":\"two\"}").error()).isTrue();
        assertThat(call("search_units", "{\"min_rent\":-5}").error()).isTrue();
    }

    @Test
    void unitDetailsIncludeTenantAndOverdueAmount() throws Exception {
        var d = data(call("get_unit_details", "{\"unit_code\":\"" + chronicUnit() + "\"}"));

        assertThat(d.at("/currentTenant/nameEn").asText()).isEqualTo("Ahmed Al-Harbi");
        assertThat(d.at("/currentTenant/nameAr").asText()).isEqualTo("أحمد الحربي");
        assertThat(d.at("/currentTenant/overdueMonths").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void unitDetailsForUnknownUnitIsAnError() throws Exception {
        assertThat(call("get_unit_details", "{\"unit_code\":\"Z-999\"}").error()).isTrue();
        assertThat(call("get_unit_details", "{}").content()).contains("unit_code is required");
    }

    @Test
    void overdueTenantsAreSortedAndIncludeTheChronicPayer() throws Exception {
        var d = data(call("find_overdue_tenants", "{\"min_days_overdue\":30}"));

        assertThat(d.get("totalTenants").asInt()).isPositive();
        var first = d.get("tenants").get(0);
        assertThat(first.get("daysOverdue").asInt()).isGreaterThan(30);
        var days = d.get("tenants").findValues("daysOverdue").stream().map(JsonNode::asInt).toList();
        assertThat(days).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(d.get("tenants").findValuesAsText("nameEn")).contains("Ahmed Al-Harbi");
    }

    @Test
    void occupancySummaryAddsUp() throws Exception {
        var d = data(call("get_occupancy_summary", "{\"building\":\"a\"}"));

        assertThat(d.get("building").asText()).isEqualTo("A");
        assertThat(d.get("totalUnits").asInt()).isEqualTo(25);
        assertThat(d.get("occupiedUnits").asInt() + d.get("vacantUnits").asInt() + d.get("maintenanceUnits").asInt())
                .isEqualTo(25);
        assertThat(d.get("rentExpected").decimalValue()).isPositive();
        assertThat(d.get("rentCollected").decimalValue()).isLessThanOrEqualTo(d.get("rentExpected").decimalValue());
    }

    @Test
    void occupancySummaryWholePortfolioAndPastMonth() throws Exception {
        var portfolio = data(call("get_occupancy_summary", "{}"));
        assertThat(portfolio.get("building").asText()).isEqualTo("ALL");
        assertThat(portfolio.get("totalUnits").asInt()).isEqualTo(150);

        var lastYear = java.time.YearMonth.now().minusMonths(6).toString();
        var past = data(call("get_occupancy_summary", "{\"month\":\"" + lastYear + "\"}"));
        assertThat(past.get("collectionRatePercent").decimalValue()).isGreaterThan(java.math.BigDecimal.valueOf(80));
    }

    @Test
    void occupancySummaryValidatesBuildingAndMonth() throws Exception {
        var unknown = call("get_occupancy_summary", "{\"building\":\"Q\"}");
        assertThat(unknown.error()).isTrue();
        assertThat(unknown.content()).contains("[A, B, C, D, E, F]");
        assertThat(call("get_occupancy_summary", "{\"month\":\"March\"}").error()).isTrue();
    }

    @Test
    void paymentHistoryShowsTwelveMonthsAndUnpaidOnes() throws Exception {
        var d = data(call("get_payment_history", "{\"unit_code\":\"" + chronicUnit() + "\"}"));

        assertThat(d.get("payments")).hasSize(12);
        assertThat(d.get("unpaidMonths").asInt()).isEqualTo(4);
        assertThat(d.at("/payments/0/status").asText()).isEqualTo("UNPAID");
        assertThat(d.at("/payments/11/status").asText()).startsWith("PAID");
    }

    @Test
    void paymentHistoryRejectsAFractionalNumberOfMonths() throws Exception {
        var result = call("get_payment_history", "{\"unit_code\":\"" + chronicUnit() + "\",\"months\":2.7}");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("months must be an integer");
    }

    @Test
    void paymentHistoryAcceptsAWholeNumberWrittenWithADecimalPoint() throws Exception {
        var d = data(call("get_payment_history", "{\"unit_code\":\"" + chronicUnit() + "\",\"months\":2.0}"));

        assertThat(d.get("payments")).hasSize(2);
    }

    @Test
    void paymentHistoryByAmbiguousNameAsksForClarification() throws Exception {
        var result = call("get_payment_history", "{\"tenant_name\":\"Ahmed\"}");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("match").contains("unit_code");
    }

    @Test
    void paymentHistoryByUniqueArabicName() throws Exception {
        var d = data(call("get_payment_history", "{\"tenant_name\":\"أحمد الحربي\",\"months\":3}"));

        assertThat(d.get("payments")).hasSize(3);
        assertThat(d.get("unitCode").asText()).isEqualTo(chronicUnit());
    }

    @Test
    void draftsArabicRentReminderWithoutSendingAnything() throws Exception {
        var d = data(call("draft_tenant_message",
                "{\"unit_code\":\"" + chronicUnit() + "\",\"purpose\":\"RENT_REMINDER\",\"language\":\"AR\"}"));

        assertThat(d.get("sent").asBoolean()).isFalse();
        assertThat(d.get("language").asText()).isEqualTo("ar");
        assertThat(d.get("subject").asText()).contains("تذكير");
        assertThat(d.get("body").asText()).contains("أحمد الحربي").contains("ريال");
    }

    @Test
    void draftsEnglishReminderMentioningTheAmount() throws Exception {
        var d = data(call("draft_tenant_message",
                "{\"tenant_name\":\"Ahmed\",\"unit_code\":\"" + chronicUnit() + "\",\"purpose\":\"RENT_REMINDER\",\"language\":\"EN\"}"));

        assertThat(d.get("body").asText()).contains("Dear Ahmed Al-Harbi").contains("SAR").contains("4 month(s)");
    }

    @Test
    void reminderForUpToDateTenantIsRefused() throws Exception {
        var paidUpUnit = jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id
                where not exists (select 1 from payments p where p.lease_id = l.id and p.paid_on is null)
                limit 1""", String.class);

        var result = call("draft_tenant_message", "{\"unit_code\":\"" + paidUpUnit + "\",\"purpose\":\"RENT_REMINDER\"}");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("no overdue rent");
    }

    @Test
    void renewalDraftWorksForAnyTenantAndNameMismatchIsCaught() throws Exception {
        var ok = data(call("draft_tenant_message", "{\"unit_code\":\"" + chronicUnit() + "\",\"purpose\":\"LEASE_RENEWAL\",\"language\":\"EN\"}"));
        assertThat(ok.get("subject").asText()).containsIgnoringCase("renewal");

        var mismatch = call("draft_tenant_message",
                "{\"tenant_name\":\"Nobody\",\"unit_code\":\"" + chronicUnit() + "\",\"purpose\":\"LEASE_RENEWAL\"}");
        assertThat(mismatch.error()).isTrue();
        assertThat(mismatch.content()).contains("does not match");
    }

    @Test
    void draftOnVacantUnitExplainsWhy() throws Exception {
        var vacant = jdbc.queryForObject("select code from units where status = 'VACANT' limit 1", String.class);

        var result = call("draft_tenant_message", "{\"unit_code\":\"" + vacant + "\",\"purpose\":\"LEASE_RENEWAL\"}");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("no active lease");
    }
}
