package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant 7 is a seeded non-payer with four unpaid months. Their oldest invoice is then paid in part: the row keeps
 * {@code paid_on} null, so every read path has to count only what is still owed. Rolled back after every test.
 */
@Transactional
class PartialPaymentsIT extends PostgresIntegrationTest {

    private static final BigDecimal PART = new BigDecimal("1000.00");

    @Autowired
    ToolRegistry registry;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    private String unit;
    private BigDecimal rent;
    private int overdueMonths;
    private BigDecimal owedBeforePart;

    @BeforeEach
    void payTheOldestUnpaidInvoiceInPart() {
        unit = jdbc.queryForObject("select u.code from leases l join units u on u.id = l.unit_id where l.tenant_id = 7", String.class);
        rent = jdbc.queryForObject("select l.monthly_rent from leases l where l.tenant_id = 7", BigDecimal.class);
        // "overdue" means due before today, so on the first day of a month the current invoice is not counted yet
        overdueMonths = jdbc.queryForObject("""
                select count(*) from payments p join leases l on l.id = p.lease_id
                where l.tenant_id = 7 and p.paid_on is null and p.due_date < current_date""", Integer.class);
        owedBeforePart = rent.multiply(BigDecimal.valueOf(overdueMonths));
        jdbc.update("""
                update payments set paid_amount = ?
                where id = (select p.id from payments p join leases l on l.id = p.lease_id
                            where l.tenant_id = 7 and p.paid_on is null order by p.period limit 1)""", PART);
    }

    private JsonNode data(String tool, String args) throws Exception {
        var result = registry.execute(tool, json.readTree(args));
        assertThat(result.error()).as(result.content()).isFalse();
        return json.readTree(result.content());
    }

    private static void assertSame(JsonNode actual, BigDecimal expected) {
        assertThat(actual.decimalValue()).isEqualByComparingTo(expected);
    }

    @Test
    void overdueListCountsOnlyTheRemainingAmount() throws Exception {
        var d = data("find_overdue_tenants", "{}");

        var tenant = d.get("tenants").get(0);
        assertThat(tenant.get("unitCode").asText()).isEqualTo(unit);
        assertThat(tenant.get("unpaidMonths").asInt()).as("the part-paid month is still unpaid").isEqualTo(overdueMonths);
        assertSame(tenant.get("unpaidAmount"), owedBeforePart.subtract(PART));
    }

    @Test
    void unitDetailsShowTheRemainingOverdueAmount() throws Exception {
        var d = data("get_unit_details", "{\"unit_code\":\"" + unit + "\"}");

        assertThat(d.at("/currentTenant/overdueMonths").asInt()).isEqualTo(overdueMonths);
        assertSame(d.at("/currentTenant/overdueAmount"), owedBeforePart.subtract(PART));
    }

    @Test
    void paymentHistoryMarksTheRowPartialAndShowsWhatIsLeft() throws Exception {
        var d = data("get_payment_history", "{\"unit_code\":\"" + unit + "\"}");

        var partial = d.get("payments").get(3);
        assertThat(partial.get("status").asText()).isEqualTo("PARTIAL");
        assertSame(partial.get("amount"), rent);
        assertSame(partial.get("paidAmount"), PART);
        assertSame(partial.get("remainingAmount"), rent.subtract(PART));
        assertThat(partial.get("paidOn").isNull()).isTrue();
        assertThat(d.get("payments").get(0).get("status").asText()).isEqualTo("UNPAID");
        assertSame(d.get("payments").get(0).get("remainingAmount"), rent);
        assertThat(d.get("unpaidMonths").asInt()).as("a part-paid month is still owed").isEqualTo(4);
    }

    @Test
    void reminderQuotesTheRemainingAmountNotTheInvoiceTotal() throws Exception {
        var d = data("draft_tenant_message", "{\"unit_code\":\"" + unit + "\",\"purpose\":\"RENT_REMINDER\",\"language\":\"EN\"}");

        var remaining = owedBeforePart.subtract(PART).stripTrailingZeros().toPlainString();
        assertThat(d.get("body").asText()).contains("SAR " + remaining).contains(overdueMonths + " month(s)");
    }

    @Test
    void reminderKeepsBothCentDigitsOfTheRemainingAmount() throws Exception {
        jdbc.update("""
                update payments set paid_amount = 1000.50
                where id = (select p.id from payments p join leases l on l.id = p.lease_id
                            where l.tenant_id = 7 and p.paid_on is null order by p.period limit 1)""");

        var english = data("draft_tenant_message", "{\"unit_code\":\"" + unit + "\",\"purpose\":\"RENT_REMINDER\",\"language\":\"EN\"}");
        var arabic = data("draft_tenant_message", "{\"unit_code\":\"" + unit + "\",\"purpose\":\"RENT_REMINDER\",\"language\":\"AR\"}");

        var remaining = owedBeforePart.subtract(new BigDecimal("1000.50"));
        assertThat(remaining.remainder(BigDecimal.ONE)).as("the remainder has fifty cents").isEqualByComparingTo("0.50");
        assertThat(english.get("body").asText()).contains("SAR " + remaining.toPlainString() + ",");
        assertThat(arabic.get("body").asText()).contains(remaining.toPlainString() + " ريال سعودي");
    }

    @Test
    void anInvoiceSettledInFullLeavesTheOverdueList() throws Exception {
        jdbc.update("""
                update payments set paid_amount = amount, paid_on = current_date
                where id = (select p.id from payments p join leases l on l.id = p.lease_id
                            where l.tenant_id = 7 and p.paid_on is null order by p.period limit 1)""");

        var d = data("get_unit_details", "{\"unit_code\":\"" + unit + "\"}");

        assertThat(d.at("/currentTenant/overdueMonths").asInt()).isEqualTo(overdueMonths - 1);
        assertSame(d.at("/currentTenant/overdueAmount"), rent.multiply(BigDecimal.valueOf(overdueMonths - 1)));
    }
}
