package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.offline.OfflineLlmClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What the rule-based assistant does with a request to find a tenant by name, and how it writes what comes back. */
class OfflineTenantLookupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration LIMIT = Duration.ofSeconds(5);
    /** The production JVM runs with -Xss256k, a quarter of what a test thread has. */
    private static final long SMALL_STACK_BYTES = 256 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final OfflineLlmClient client = new OfflineLlmClient(json);

    private LlmResponse ask(String question) {
        return client.complete(new LlmRequest("", List.of(Message.user(question)), List.of(), TIMEOUT), s -> { });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Find tenant Ahmed | Ahmed
            find the tenant Ahmed Al-Harbi please | Ahmed Al-Harbi
            Who is tenant Ahmed Al-Otaibi? | Ahmed Al-Otaibi
            Look up tenant Sara | Sara
            Search for tenant Sara Al-Qahtani | Sara Al-Qahtani
            Which tenants are called Ahmed? | Ahmed
            Which tenant is named Khalid? | Khalid
            Who is the tenant Omar Al-Anazi? | Omar Al-Anazi
            ابحث عن المستأجر أحمد | أحمد
            من هو المستأجر أحمد الحربي؟ | أحمد الحربي
            """)
    void aRequestToFindATenantByNameAsksTheToolThatListsEveryMatch(String question, String name) {
        var response = ask(question);

        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        var call = response.toolUses().get(0);
        assertThat(call.name()).isEqualTo("find_tenant");
        assertThat(call.input().get("tenant_name").asText()).isEqualTo(name);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Who is the tenant of unit A-203? | get_unit_details
            Find tenant Ahmed in unit A-203 | get_unit_details
            Which tenant is more than 30 days late? | find_overdue_tenants
            Which tenants are late on rent? | find_overdue_tenants
            Draft a rent reminder for tenant Ahmed | draft_tenant_message
            Record a payment of 1500 for tenant Ahmed in unit A-203 | record_payment
            Find vacant units in Riyadh | search_units
            """)
    void otherQuestionsKeepTheirOwnTool(String question, String tool) {
        assertThat(ask(question).toolUses()).extracting(ContentBlock.ToolUse::name).containsExactly(tool);
    }

    @Test
    void aRequestThatNamesNoTenantIsNotALookup() {
        for (var question : new String[] {"Find tenant", "Which tenant has the highest rent?", "Who is the tenant?",
                "ابحث عن المستأجر", "Look up tenant in"}) {
            assertThat(ask(question).toolUses()).as(question)
                    .noneMatch(use -> use.name().equals("find_tenant"));
        }
    }

    /** The router has no list of tenants, so a word that is not written as a name is not looked up as one. */
    @Test
    void aWordAfterTenantThatIsNotWrittenAsANameIsNotALookup() {
        for (var question : new String[] {"Find tenant details for Sara", "find tenant ahmed", "Which tenants paid in Ramadan?",
                "Which tenant is more than 30 days late?", "من هو المستأجر الأقدم؟", "ابحث عن المستأجر المتأخر"}) {
            assertThat(ask(question).toolUses()).as(question)
                    .noneMatch(use -> use.name().equals("find_tenant"));
        }
    }

    @Test
    void anArabicNameWithItsFamilyNameIsALookupEvenWithTheDefiniteArticle() {
        var call = ask("ابحث عن المستأجر أحمد الحربي").toolUses().get(0);

        assertThat(call.name()).isEqualTo("find_tenant");
        assertThat(call.input().get("tenant_name").asText()).isEqualTo("أحمد الحربي");
    }

    @Test
    void aWordTooLongForANameIsNotSentToTheTool() {
        assertThat(ask("Find tenant " + "A".repeat(5_000)).toolUses())
                .noneMatch(use -> use.name().equals("find_tenant"));
    }

    @Test
    void hostileTextIsReadInBoundedTimeOnASmallStack() {
        var unbrokenWord = "Find tenant " + "ا".repeat(500_000);
        var spaces = "find" + " ".repeat(200_000) + "tenant Ahmed";
        var repeated = "find tenant ".repeat(20_000);
        var particles = "which tenants " + "are ".repeat(50_000) + "called";
        var names = "Find tenant " + "Al-A ".repeat(100_000);

        for (var question : new String[] {unbrokenWord, spaces, repeated, particles, names}) {
            onSmallStack(question);
        }
    }

    private void onSmallStack(String question) {
        var executor = Executors.newSingleThreadExecutor(
                task -> new Thread(null, task, "small-stack", SMALL_STACK_BYTES));
        try {
            executor.submit(() -> ask(question)).get(LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("A question of " + question.length() + " characters could not be read on a "
                    + SMALL_STACK_BYTES / 1024 + " KB stack", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Reading a question of " + question.length() + " characters took more than "
                    + LIMIT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private String answer(String question, String result) {
        var call = new ContentBlock.ToolUse("t1", "find_tenant", json.createObjectNode().put("tenant_name", "x"));
        var messages = List.of(Message.user(question),
                new Message(Message.Role.ASSISTANT, List.of(call)),
                new Message(Message.Role.USER, List.of(new ContentBlock.ToolResult("t1", result, false))));
        var response = client.complete(new LlmRequest("", messages, List.of(), TIMEOUT), s -> { });
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        return response.text();
    }

    private static final String SEVERAL = """
            {"count":2,"shown":2,"tenants":[
             {"nameEn":"Ahmed Al-Harbi","nameAr":"أحمد الحربي","unitCode":"B-203","activeLease":true},
             {"nameEn":"Ahmed Al-Otaibi","nameAr":"أحمد العتيبي","unitCode":"A-203","activeLease":true}]}""";

    @Test
    void listsEveryMatchWithItsUnitAndAsksWhichOneIsMeant() {
        var text = answer("Find tenant Ahmed", SEVERAL);

        assertThat(text).contains("2 tenants match", "Ahmed Al-Harbi, unit B-203", "Ahmed Al-Otaibi, unit A-203",
                "Which one do you mean?").doesNotContain("find_tenant");
    }

    @Test
    void writesTheListInArabicWithTheArabicNames() {
        var text = answer("ابحث عن المستأجر أحمد", SEVERAL);

        assertThat(text).contains("أحمد الحربي، الوحدة B-203", "أحمد العتيبي، الوحدة A-203", "أي مستأجر تقصد؟")
                .doesNotContain("Ahmed");
    }

    @Test
    void saysSoWhenNobodyMatches() {
        assertThat(answer("Find tenant Zaynab", "{\"count\":0,\"shown\":0,\"tenants\":[]}"))
                .isEqualTo("No tenant matches that name.");
        assertThat(answer("ابحث عن المستأجر زينب", "{\"count\":0,\"shown\":0,\"tenants\":[]}"))
                .isEqualTo("لا يوجد مستأجر بهذا الاسم.");
    }

    @Test
    void doesNotAskWhichOneForASingleMatchAndNotesATenantWithoutALease() {
        var text = answer("Find tenant Qwxyz", """
                {"count":1,"shown":1,"tenants":[{"nameEn":"Qwxyz Formerly","nameAr":"كويكس سابق","activeLease":false}]}""");

        assertThat(text).startsWith("1 tenant matches:").contains("Qwxyz Formerly, no active lease")
                .doesNotContain("Which one");
    }

    @Test
    void saysHowManyMatchedWhenOnlySomeAreListed() {
        var text = answer("Find tenant Al-", """
                {"count":25,"shown":1,"tenants":[
                 {"nameEn":"Ahmed Al-Harbi","nameAr":"أحمد الحربي","unitCode":"B-203","activeLease":true}]}""");

        assertThat(text).contains("25 tenants match", "Showing 1 of 25");
    }
}
