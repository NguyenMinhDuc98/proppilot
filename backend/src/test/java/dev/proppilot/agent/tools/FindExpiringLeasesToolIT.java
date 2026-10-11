package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.rag.CorpusIntegrationTest;
import dev.proppilot.rag.LeaseDocumentCatalog;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The expiring leases of the seed: migration V6 gives seven active leases an end date 14 to 56 days after the day the
 * database was made, two of them beyond the leases that get a document.
 */
class FindExpiringLeasesToolIT extends CorpusIntegrationTest {

    private static final String TOOL = "find_expiring_leases";

    @Autowired
    ToolRegistry registry;
    @Autowired
    ObjectMapper json;
    @Autowired
    LeaseRepository leases;
    @Autowired
    LeaseDocumentCatalog documents;
    @Autowired
    Clock clock;
    private ToolResult call(String args) throws Exception {
        return registry.execute(TOOL, json.readTree(args));
    }

    private JsonNode data(ToolResult result) throws Exception {
        assertThat(result.error()).as(result.content()).isFalse();
        return json.readTree(result.content());
    }

    private static List<String> texts(JsonNode array, String field) {
        var values = new ArrayList<String>();
        array.forEach(item -> values.add(item.get(field).asText()));
        return values;
    }

    /** The application's today: the day the tool counts from, which the database's own clock may not agree on. */
    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private List<String> unitsEndingWithin(int days) {
        return jdbc.queryForList("""
                select u.code from leases l join units u on u.id = l.unit_id
                where l.active and l.end_date between ? and ?
                order by l.end_date, u.code""", String.class, today(), today().plusDays(days));
    }

    private List<String> daysLeftOf(List<String> endDates) {
        return endDates.stream()
                .map(end -> String.valueOf(ChronoUnit.DAYS.between(today(), LocalDate.parse(end)))).toList();
    }

    @Test
    void listsTheLeasesThatEndWithinTheWindowSoonestFirstWithTheirDocumentFlag() throws Exception {
        var result = call("{\"within_days\":60}");

        var d = data(result);
        assertThat(d.get("withinDays").asInt()).isEqualTo(60);
        assertThat(d.get("totalLeases").asInt()).isEqualTo(7);
        assertThat(d.get("shown").asInt()).isEqualTo(7);
        var expected = unitsEndingWithin(60);
        assertThat(texts(d.get("leases"), "unitCode")).containsExactlyElementsOf(expected);
        assertThat(texts(d.get("leases"), "endDate")).isSorted();
        assertThat(texts(d.get("leases"), "daysLeft")).containsExactlyElementsOf(
                daysLeftOf(texts(d.get("leases"), "endDate")));
        assertThat(d.get("leases")).extracting(lease -> lease.get("daysLeft").asInt()).isSorted()
                .allSatisfy(days -> assertThat(days).isBetween(10, 60));
        assertThat(d.get("leases")).extracting(lease -> lease.get("hasLeaseDocument").asBoolean())
                .containsExactly(true, false, true, false, true, true, true);
        assertThat(result.summary()).isEqualTo("7 leases end within 60 days");
    }

    @Test
    void eachEntryCarriesTheUnitTheTenantInBothLanguagesTheDatesAndTheRent() throws Exception {
        var d = data(call("{\"within_days\":20}"));

        assertThat(d.get("leases")).hasSize(1);
        var lease = d.get("leases").get(0);
        var stored = jdbc.queryForMap("""
                select u.code, t.name_en, t.name_ar, l.end_date, l.monthly_rent
                from leases l join units u on u.id = l.unit_id join tenants t on t.id = l.tenant_id
                where l.active and l.end_date between ? and ?""", today(), today().plusDays(20));
        assertThat(lease.fieldNames()).toIterable().containsExactly("unitCode", "tenantNameEn", "tenantNameAr",
                "endDate", "daysLeft", "monthlyRent", "hasLeaseDocument");
        assertThat(lease.get("unitCode").asText()).isEqualTo(stored.get("code"));
        assertThat(lease.get("tenantNameEn").asText()).isEqualTo(stored.get("name_en"));
        assertThat(lease.get("tenantNameAr").asText()).isEqualTo(stored.get("name_ar"));
        assertThat(lease.get("endDate").asText()).isEqualTo(stored.get("end_date").toString());
        assertThat(lease.get("daysLeft").asInt())
                .isEqualTo((int) ChronoUnit.DAYS.between(today(), LocalDate.parse(stored.get("end_date").toString())));
        assertThat(lease.get("monthlyRent").decimalValue()).isEqualByComparingTo(stored.get("monthly_rent").toString());
        assertThat(lease.get("hasLeaseDocument").asBoolean()).isTrue();
    }

    @Test
    void theWindowDefaultsToSixtyDays() throws Exception {
        var d = data(call("{}"));

        assertThat(d.get("withinDays").asInt()).isEqualTo(60);
        assertThat(texts(d.get("leases"), "unitCode")).containsExactlyElementsOf(unitsEndingWithin(60));
    }

    @Test
    void aShorterWindowKeepsOnlyTheLeasesInsideIt() throws Exception {
        var d = data(call("{\"within_days\":30}"));

        assertThat(d.get("totalLeases").asInt()).isEqualTo(3);
        assertThat(texts(d.get("leases"), "unitCode")).containsExactlyElementsOf(unitsEndingWithin(30));
    }

    @Test
    void aWindowWithNoLeaseEndingSaysSoWithAnEmptyList() throws Exception {
        var result = call("{\"within_days\":10}");

        var d = data(result);
        assertThat(d.get("totalLeases").asInt()).isZero();
        assertThat(d.get("shown").asInt()).isZero();
        assertThat(d.get("leases").isArray()).isTrue();
        assertThat(d.get("leases")).isEmpty();
        assertThat(result.summary()).isEqualTo("0 leases end within 10 days");
    }

    @Test
    void theSummaryIsSingularForOneLease() throws Exception {
        assertThat(call("{\"within_days\":20}").summary()).isEqualTo("1 lease ends within 20 days");
    }

    @Test
    void aYearWindowShowsAtMostFifteenAndCountsAll() throws Exception {
        var d = data(call("{\"within_days\":365}"));

        int total = jdbc.queryForObject("""
                select count(*) from leases where active and end_date between ? and ?""", Integer.class, today(),
                today().plusDays(365));
        assertThat(d.get("totalLeases").asInt()).isEqualTo(total);
        assertThat(d.get("shown").asInt()).isEqualTo(Math.min(total, 15));
        assertThat(d.get("leases")).hasSize(Math.min(total, 15));
        assertThat(texts(d.get("leases"), "unitCode").subList(0, 7)).containsExactlyElementsOf(unitsEndingWithin(60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "366", "-5", "2.5", "\"60\"", "true", "[60]", "1e999", "99999999999"})
    void aWindowOutsideOneToThreeHundredSixtyFiveOrNotAnIntegerIsRefused(String days) throws Exception {
        var result = call("{\"within_days\":" + days + "}");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).startsWith("Invalid input: within_days must be");
    }

    @Test
    void theWindowIncludesTodayAndTheLastDayAndNothingAroundThem() throws Exception {
        var endDate = jdbc.queryForObject("""
                select min(end_date) from leases where active and end_date > ?""", LocalDate.class, today());
        var unit = jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id
                where l.active and l.end_date = ? order by u.code limit 1""", String.class, endDate);

        assertThat(foundOn(endDate, 1)).as("a lease that ends today").contains(unit);
        assertThat(foundOn(endDate.minusDays(5), 5)).as("on the last day of the window").contains(unit);
        assertThat(foundOn(endDate.minusDays(5), 4)).as("a day after the window").doesNotContain(unit);
        assertThat(foundOn(endDate.plusDays(1), 365)).as("a lease that ended yesterday").doesNotContain(unit);
    }

    /** The units the tool lists when today is the given day. */
    private List<String> foundOn(LocalDate today, int withinDays) throws Exception {
        var clockAtToday = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var tool = new FindExpiringLeasesTool(leases, documents, clockAtToday, json);

        var d = data(tool.execute(json.readTree("{\"within_days\":" + withinDays + "}")));

        return texts(d.get("leases"), "unitCode");
    }

    @Test
    void anInactiveLeaseIsNeverListed() throws Exception {
        var ended = unitsEndingWithin(30).get(1);
        long leaseId = jdbc.queryForObject("""
                select l.id from leases l join units u on u.id = l.unit_id where l.active and u.code = ?""",
                Long.class, ended);

        try {
            jdbc.update("update leases set active = false where id = ?", leaseId);

            var d = data(call("{\"within_days\":60}"));

            assertThat(texts(d.get("leases"), "unitCode")).doesNotContain(ended);
            assertThat(d.get("totalLeases").asInt()).isEqualTo(6);
        } finally {
            jdbc.update("update leases set active = true where id = ?", leaseId);
        }
    }
}
