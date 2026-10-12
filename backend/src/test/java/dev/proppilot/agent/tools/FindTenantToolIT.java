package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tool that resolves a name to its candidates, against the seeded database: six tenants are called Ahmed, and
 * every name pair is unique.
 */
class FindTenantToolIT extends PostgresIntegrationTest {

    private static final String[] AHMEDS = {"Ahmed Al-Harbi", "Ahmed Al-Otaibi", "Ahmed Al-Zahrani",
            "Ahmed Al-Dosari", "Ahmed Al-Shammari", "Ahmed Al-Subaie"};

    @Autowired
    ToolRegistry registry;
    @Autowired
    TenantResolver resolver;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    private ToolResult call(String args) throws Exception {
        return registry.execute("find_tenant", json.readTree(args));
    }

    private ToolResult call(String tool, String args) throws Exception {
        return registry.execute(tool, json.readTree(args));
    }

    private JsonNode data(ToolResult result) throws Exception {
        assertThat(result.error()).as(result.content()).isFalse();
        return json.readTree(result.content());
    }

    private JsonNode named(String name) throws Exception {
        return data(call(json.createObjectNode().put("tenant_name", name).toString()));
    }

    private String unitOf(String nameEn) {
        return jdbc.queryForObject("""
                select u.code from leases l join units u on u.id = l.unit_id join tenants t on t.id = l.tenant_id
                where l.active and t.name_en = ?""", String.class, nameEn);
    }

    @Test
    void aFirstNameReturnsEveryTenantWhoHasItWithTheirUnits() throws Exception {
        var found = named("Ahmed");

        assertThat(found.get("count").asInt()).isEqualTo(6);
        assertThat(found.get("shown").asInt()).isEqualTo(6);
        assertThat(found.get("tenants")).hasSize(6).allSatisfy(match -> {
            assertThat(match.get("activeLease").asBoolean()).isTrue();
            assertThat(match.get("unitCode").asText()).isEqualTo(unitOf(match.get("nameEn").asText()));
            assertThat(match.get("nameAr").asText()).startsWith("أحمد");
        });
        assertThat(found.get("tenants")).extracting(match -> match.get("nameEn").asText()).containsExactlyInAnyOrder(AHMEDS);
    }

    @Test
    void theArabicFirstNameFindsTheSameSix() throws Exception {
        assertThat(named("أحمد").get("tenants")).extracting(match -> match.get("nameEn").asText())
                .containsExactlyInAnyOrder(AHMEDS);
    }

    @Test
    void aFullNameFindsOneTenant() throws Exception {
        var found = named("Ahmed Al-Otaibi");

        assertThat(found.get("count").asInt()).isEqualTo(1);
        assertThat(found.get("tenants").get(0).get("unitCode").asText()).isEqualTo(unitOf("Ahmed Al-Otaibi"));
    }

    @Test
    void theCaseOfTheEnglishNameDoesNotMatter() throws Exception {
        assertThat(named("ahmed al-otaibi").get("count").asInt()).isEqualTo(1);
    }

    @Test
    void aNameThatNobodyHasIsAnEmptyListAndNotAnError() throws Exception {
        var result = call("{\"tenant_name\":\"Zaynab\"}");

        assertThat(result.error()).isFalse();
        var found = json.readTree(result.content());
        assertThat(found.get("count").asInt()).isZero();
        assertThat(found.get("tenants")).isEmpty();
        assertThat(result.summary()).isEqualTo("No tenant matches");
    }

    @Test
    void aShortNameThatMatchesManyIsCappedAndSaysHowManyMatched() throws Exception {
        var found = named("Al-");

        assertThat(found.get("count").asInt()).isGreaterThan(FindTenantTool.MAX_SHOWN);
        assertThat(found.get("shown").asInt()).isEqualTo(FindTenantTool.MAX_SHOWN);
        assertThat(found.get("tenants")).hasSize(FindTenantTool.MAX_SHOWN);
    }

    @Test
    void theCandidatesAreExactlyTheOnesTheOtherToolsRefuseAsAmbiguous() throws Exception {
        var found = named("Ahmed");
        var refusal = call("get_payment_history", "{\"tenant_name\":\"Ahmed\"}");

        // the refusal lists the first five, in the same order as the tool, and counts the same candidates
        var firstFive = new ArrayList<String>();
        found.get("tenants").forEach(match -> {
            if (firstFive.size() < 5) {
                firstFive.add(match.get("nameEn").asText() + " (unit " + match.get("unitCode").asText() + ")");
            }
        });
        assertThat(refusal.error()).isTrue();
        assertThat(refusal.content()).startsWith(found.get("count").asInt() + " tenants match 'Ahmed'")
                .endsWith("First matches: " + String.join(", ", firstFive));
    }

    @Test
    void anArabicNameWrittenWithoutTheHamzaFindsTheSameTenants() throws Exception {
        assertThat(named("احمد").get("tenants")).extracting(match -> match.get("nameEn").asText())
                .containsExactlyInAnyOrder(AHMEDS);
        assertThat(named("احمد الحربي").get("count").asInt()).isEqualTo(1);
        assertThat(named("أحمد الحربي").get("count").asInt()).isEqualTo(1);
    }

    @Test
    void aFamilyNameWithASpaceInsteadOfTheHyphenFindsTheSameTenants() throws Exception {
        var hyphen = named("Al-Harbi");
        var space = named("al harbi");

        assertThat(hyphen.get("count").asInt()).isGreaterThan(1);
        assertThat(space.get("tenants")).isEqualTo(hyphen.get("tenants"));
        assertThat(named("ahmed al harbi").get("count").asInt()).isEqualTo(1);
    }

    @Test
    void foldingOnlyAddsToWhatTheNameMatchedAsWritten() throws Exception {
        for (var name : new String[] {"Ahmed", "Al-", "أحمد", "الحربي", "Sara Al-Qahtani", "ana"}) {
            var asWritten = jdbc.queryForList("""
                    select id from tenants
                    where lower(name_en) like lower('%' || ? || '%') or name_ar like '%' || ? || '%' order by id""",
                    Long.class, name, name);

            assertThat(resolver.candidates(name)).as(name).extracting(candidate -> candidate.tenant().getId())
                    .containsAll(asWritten);
        }
    }

    @Test
    void theResultHoldsOnlyWhoTheyAreAndWhereTheyLive() throws Exception {
        var found = named("Ahmed");

        for (var match : found.get("tenants")) {
            assertThat(match.fieldNames()).toIterable().containsExactlyInAnyOrder("nameEn", "nameAr", "unitCode",
                    "activeLease");
        }
        assertThat(found.fieldNames()).toIterable().containsExactlyInAnyOrder("count", "shown", "tenants");
    }

    @Test
    void aTenantWithoutAnActiveLeaseIsListedWithoutAUnit() throws Exception {
        long id = jdbc.queryForObject("""
                insert into tenants (name_en, name_ar, phone, email, preferred_language)
                values ('Qwxyz Formerly', 'كويكس سابق', '+966500000000', 'qwxyz@example.test', 'en') returning id""",
                Long.class);
        try {
            var found = named("Qwxyz");

            assertThat(found.get("count").asInt()).isEqualTo(1);
            var match = found.get("tenants").get(0);
            assertThat(match.get("activeLease").asBoolean()).isFalse();
            assertThat(match.has("unitCode")).isFalse();
        } finally {
            jdbc.update("delete from tenants where id = ?", id);
        }
    }

    @Test
    void aTenantWithTwoActiveLeasesIsListedOncePerUnit() throws Exception {
        var vacant = jdbc.queryForObject("select id from units where status = 'VACANT' order by id limit 1", Long.class);
        var vacantCode = jdbc.queryForObject("select code from units where id = ?", String.class, vacant);
        long leaseId = jdbc.queryForObject("""
                insert into leases (unit_id, tenant_id, start_date, end_date, monthly_rent, active)
                values (?, 7, current_date, current_date + 365, 1000, true) returning id""", Long.class, vacant);
        try {
            var candidates = resolver.candidates("أحمد الحربي");

            assertThat(candidates).hasSize(2);
            var units = new ArrayList<String>();
            candidates.forEach(candidate -> units.add(candidate.activeLease().getUnit().getCode()));
            assertThat(units).contains(vacantCode).hasSize(2);
            assertThat(named("أحمد الحربي").get("count").asInt()).isEqualTo(2);
            assertThat(resolver.resolve(Optional.of("أحمد الحربي"), Optional.empty()))
                    .isInstanceOfSatisfying(TenantResolver.Resolution.Failed.class,
                            failed -> assertThat(failed.problem()).isEqualTo(TenantResolver.Problem.AMBIGUOUS_TENANT));
        } finally {
            jdbc.update("delete from leases where id = ?", leaseId);
        }
    }

    @Test
    void aNameIsRequiredAndBounded() throws Exception {
        assertThat(call("{}").error()).isTrue();
        assertThat(call("{}").content()).contains("tenant_name is required");
        assertThat(call("{\"tenant_name\":\"   \"}").content()).contains("tenant_name is required");
        assertThat(call(json.createObjectNode().put("tenant_name", "a".repeat(101)).toString()).content())
                .contains("at most 100 characters");
        assertThat(call("{\"tenant_name\":7}").content()).contains("tenant_name must be a string");
    }

    @Test
    void aWildcardIsAnInputAndNotAnInternalFailure() throws Exception {
        var result = call("{\"tenant_name\":\"%_%\"}");

        assertThat(result.error()).isFalse();
        assertThat(json.readTree(result.content()).get("shown").asInt()).isEqualTo(FindTenantTool.MAX_SHOWN);
    }

    @Test
    void lookingUpChangesNothing() throws Exception {
        long tenants = jdbc.queryForObject("select count(*) from tenants", Long.class);
        long leases = jdbc.queryForObject("select count(*) from leases", Long.class);
        long actions = jdbc.queryForObject("select count(*) from pending_actions", Long.class);

        named("Ahmed");
        named("Sara");

        assertThat(jdbc.queryForObject("select count(*) from tenants", Long.class)).isEqualTo(tenants);
        assertThat(jdbc.queryForObject("select count(*) from leases", Long.class)).isEqualTo(leases);
        assertThat(jdbc.queryForObject("select count(*) from pending_actions", Long.class)).isEqualTo(actions);
    }
}
