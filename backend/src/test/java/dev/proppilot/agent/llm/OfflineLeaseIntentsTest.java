package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.offline.OfflineLlmClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What the offline assistant does with questions about leases, in English and Arabic. */
class OfflineLeaseIntentsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OfflineLlmClient client = new OfflineLlmClient(JSON);

    private LlmResponse ask(List<Message> messages) {
        return client.complete(new LlmRequest("", messages, List.of(), TIMEOUT), s -> { });
    }

    private ContentBlock.ToolUse route(String question) {
        var response = ask(List.of(Message.user(question)));
        assertThat(response.stopReason()).as(question).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.toolUses()).hasSize(1);
        return response.toolUses().get(0);
    }

    private static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            What is the late fee for unit A-504? | late payment fee penalty | غرامة التأخير
            What is the penalty if rent is paid late in unit A-504? | late payment fee penalty | غرامة التأخير
            What notice period does unit A-504 require? | notice period | مدة الإشعار
            How many days notice must the tenant of A-504 give? | notice period | مدة الإشعار
            Does the lease of unit A-504 renew automatically? | renewal | مدة العقد والتجديد
            Who is responsible for repairs in unit A-504? | maintenance repairs | الصيانة والإصلاحات
            Who pays for maintenance in unit A-504? | maintenance repairs | الصيانة والإصلاحات
            Are pets allowed in unit A-504? | pets | الحيوانات الأليفة
            What is the early termination fee for unit A-504? | early termination | الإنهاء المبكر
            Can the tenant of A-504 break the lease early? | early termination | الإنهاء المبكر
            How much is the security deposit for unit A-504? | security deposit | مبلغ التأمين
            Can the tenant of A-504 sublet the apartment? | subletting | التأجير من الباطن
            When may the landlord enter unit A-504 for an inspection? | inspection | المعاينة ودخول العقار
            What are the special conditions of unit A-504? | special conditions | شروط خاصة
            """)
    void routesAnEnglishLeaseQuestionToTheSearchWithTheWordsOfTheClauseInBothLanguages(String question,
                                                                                       String english,
                                                                                       String arabic) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("unit_code").asText()).isEqualTo("A-504");
        assertThat(call.input().get("query").asText()).contains(english).contains(arabic);
        assertThat(call.input().has("unit_codes")).isFalse();
        assertThat(call.input().has("tenant_name")).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ما هي غرامة التأخير للوحدة A-504؟ | غرامة التأخير
            ما مدة الإشعار في عقد الوحدة A-504؟ | مدة الإشعار
            ما شروط التجديد للوحدة A-504؟ | مدة العقد والتجديد
            هل يتجدد عقد الوحدة A-504 تلقائيا؟ | مدة العقد والتجديد
            من المسؤول عن الصيانة في الوحدة A-504؟ | الصيانة والإصلاحات
            مسؤولية الصيانة للوحدة A-504 | الصيانة والإصلاحات
            هل يُسمح بالحيوانات الأليفة في الوحدة A-504؟ | الحيوانات الأليفة
            ما رسوم الإنهاء المبكر للوحدة A-504؟ | الإنهاء المبكر
            كم مبلغ التأمين للوحدة A-504؟ | مبلغ التأمين
            هل يجوز التأجير من الباطن في الوحدة A-504؟ | التأجير من الباطن
            ما الشروط الخاصة في عقد الوحدة A-504؟ | شروط خاصة
            """)
    void routesAnArabicLeaseQuestionToTheSearch(String question, String arabic) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("unit_code").asText()).isEqualTo("A-504");
        assertThat(call.input().get("query").asText()).contains(arabic);
    }

    @Test
    void theSpellingOfTheArabicWordsDoesNotMatter() {
        var withMarks = route("مَا هِيَ غَرَامَةُ التَّأْخِيرِ للوحدة A-504");
        var withoutHamza = route("ما هي غرامه التاخير للوحدة A-504");
        var tatweel = route("مسؤوليـــة الصيانة للوحدة A-504");

        assertThat(withMarks.input().get("query").asText()).contains("غرامة التأخير");
        assertThat(withoutHamza.input().get("query").asText()).contains("غرامة التأخير");
        assertThat(tatweel.input().get("query").asText()).contains("الصيانة والإصلاحات");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Who pays rent for unit A-203? | get_unit_details
            Which tenant is responsible for the overdue rent in B-203? | find_overdue_tenants
            Show me the cancelled payments for unit A-203 | get_unit_details
            Are there any payment disputes in unit A-203? | get_unit_details
            Is unit A-203 under maintenance? | get_unit_details
            Is parking included in unit A-203? | get_unit_details
            Which units are vacant in Red Sea Court? | search_units
            من يدفع الإيجار للوحدة A-203؟ | get_unit_details
            من المستأجر المسؤول عن الإيجار المتأخر في B-203؟ | find_overdue_tenants
            ما تكاليف الصيانة للوحدة A-203 | get_unit_details
            ما التكاليف الشهرية للوحدة A-203؟ | get_unit_details
            ما إجمالي تكاليف الوحدة B-101؟ | get_unit_details
            هل توجد تكاليف إضافية على الوحدة A-203؟ | get_unit_details
            كم عدد الوحدات الشاغرة في الرياض بتكاليف أقل من 5000؟ | search_units
            """)
    void aWordThatAlsoTurnsUpInQuestionsAboutRentAndUnitsKeepsItsToolWhenNoLeaseIsMentioned(String question,
                                                                                            String tool) {
        assertThat(route(question).name()).isEqualTo(tool);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Who is responsible for the plumbing under the lease of unit A-203? | maintenance repairs
            What does the contract say about who pays for the air conditioning in A-203? | maintenance repairs
            What does the lease say about cancelling the contract in unit A-203? | early termination
            What does the lease of unit A-203 say about noise? | quiet hours
            Does the lease for A-203 include parking? | special conditions
            Which court has jurisdiction under the lease of unit A-203? | governing law
            ماذا يقول عقد الوحدة A-203 عن الضوضاء؟ | قواعد السكن
            هل يشمل عقد الوحدة A-203 موقف سيارات؟ | شروط خاصة
            """)
    void aWordThatAlsoMeansSomethingElseNamesItsTopicWhenTheQuestionIsAboutALease(String question, String query) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("query").asText()).contains(query);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            What are the maintenance obligations of the tenant in A-203? | maintenance repairs
            What are the maintenance terms for unit A-203? | maintenance repairs
            What does the lease of unit A-203 say about maintenance? | maintenance repairs
            Who is responsible for maintenance in unit A-203? | maintenance repairs
            Maintenance responsibility for unit A-203 | maintenance repairs
            ما التزامات الصيانة في عقد الوحدة A-203؟ | الصيانة والإصلاحات
            ماذا يقول عقد الوحدة A-203 عن الصيانة؟ | الصيانة والإصلاحات
            من يتحمل تكلفة الصيانة في الوحدة A-203؟ | الصيانة والإصلاحات
            """)
    void maintenanceIsATopicWhenItComesWithAResponsibilityOrALeaseWord(String question, String query) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("query").asText()).contains(query);
    }

    @Test
    void maintenanceAloneIsStillAboutUnitsInMaintenance() {
        assertThat(route("Which units are in maintenance?").name()).isEqualTo("search_units");
        assertThat(route("ما الوحدات التي تحت الصيانة في الرياض؟").name()).isEqualTo("search_units");
    }

    @Test
    void theArabicWordForPetsHasToStartAWordSoCostsAreNotPets() {
        var costs = route("ما تكاليف الإنهاء المبكر للوحدة A-203؟");
        var withPrefix = route("هل يسمح للحيوانات الأليفة بالسكن في الوحدة A-203؟");

        assertThat(costs.input().get("query").asText()).contains("الإنهاء المبكر").doesNotContain("الحيوانات");
        assertThat(costs.input().get("top_k").asInt()).as("one topic in one lease").isEqualTo(2);
        assertThat(withPrefix.input().get("query").asText()).contains("الحيوانات الأليفة");
    }

    @Test
    void aPenaltyIsTheLateFeeOnlyWhenNoOtherTermClaimsIt() {
        var other = route("What is the penalty for early termination in unit A-203?");
        var breaking = route("What is the penalty for breaking the lease in unit A-203?");
        var late = route("What is the penalty if rent is paid late in unit A-203?");
        var both = route("What are the late fee and the penalty for early termination in unit A-203?");
        var arabic = route("ما غرامة الإنهاء المبكر للوحدة A-203؟");

        assertThat(other.input().get("query").asText()).contains("early termination").doesNotContain("late payment");
        assertThat(breaking.input().get("query").asText()).contains("early termination")
                .doesNotContain("late payment");
        assertThat(late.input().get("query").asText()).contains("late payment fee penalty");
        assertThat(both.input().get("query").asText()).contains("late payment", "early termination");
        assertThat(arabic.input().get("query").asText()).contains("الإنهاء المبكر").doesNotContain("غرامة التأخير");
    }

    @Test
    void aLeaseQuestionWithoutAUnitOrATenantSearchesEveryDocument() {
        var call = route("What is the notice period?");

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().has("unit_code")).isFalse();
        assertThat(call.input().has("unit_codes")).isFalse();
        assertThat(call.input().has("tenant_name")).isFalse();
    }

    @Test
    void aTenantNameIsPassedWhenThereIsNoUnit() {
        var english = route("What is the notice period for tenant Ahmed Al-Harbi?");
        var arabic = route("ما مدة الإشعار للمستأجر أحمد؟");

        assertThat(english.input().get("tenant_name").asText()).isEqualTo("Ahmed Al-Harbi");
        assertThat(arabic.input().get("tenant_name").asText()).isEqualTo("أحمد");
        assertThat(english.input().has("unit_code")).isFalse();
    }

    @Test
    void theUnitWinsOverTheTenantWhenBothAreNamed() {
        var call = route("What is the late fee for tenant Ahmed in unit A-504?");

        assertThat(call.input().get("unit_code").asText()).isEqualTo("A-504");
        assertThat(call.input().has("tenant_name")).isFalse();
    }

    @Test
    void severalUnitsAreSearchedTogether() {
        var call = route("Compare the notice period of units A-203, b101 and A-203 again");

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(texts(call.input().get("unit_codes"))).containsExactly("A-203", "B-101");
        assertThat(call.input().has("unit_code")).isFalse();
    }

    @Test
    void aQuestionAboutSeveralTermsSearchesForAllOfThemAndAsksForTwoClausesForEach() {
        var two = route("What are the late fee and the notice period for unit A-504?");
        var three = route("What are the late fee, the notice period and the pet rules for unit A-504?");

        assertThat(two.input().get("query").asText()).contains("late payment fee penalty", "notice period");
        assertThat(two.input().has("top_k")).as("four is the default").isFalse();
        assertThat(three.input().get("query").asText()).contains("late payment fee penalty", "notice period", "pets");
        assertThat(three.input().get("top_k").asInt()).isEqualTo(6);
    }

    @Test
    void oneTermInOneLeaseAsksForTwoClausesAndAnyOtherScopeForTheDefault() {
        assertThat(route("Are pets allowed in unit A-504?").input().get("top_k").asInt()).isEqualTo(2);
        assertThat(route("Are pets allowed?").input().has("top_k")).isFalse();
        assertThat(route("Are pets allowed in units A-504 and B-203?").input().has("top_k")).isFalse();
    }

    @Test
    void aQuestionAboutTheLeaseAsAWholeSearchesWithTheQuestionItself() {
        var call = route("What does the lease agreement for unit A-504 say?");

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("query").asText()).isEqualTo("What does the lease agreement for unit A-504 say?");
        assertThat(call.input().get("unit_code").asText()).isEqualTo("A-504");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Which leases end in the next 60 days? | 60
            Which leases are ending within 30 days? | 30
            Show the leases expiring in the next 2 months | 60
            Which leases expire within two weeks? | 14
            Which leases expire in the next 90 days that I should renew? | 90
            List leases ending next month | 30
            Which leases end this quarter? | 90
            Which contracts are up for renewal in the next 45 days? | 45
            Which leases need renewal in the next 60 days? | 60
            Which leases expire in 3 months? | 90
            Which units have a lease ending in the next 30 days? | 30
            Show the tenants whose lease ends in the next 30 days | 30
            Leases ending in the next 1000 days | 365
            Leases ending in the next 999999999 days | 365
            """)
    void routesAQuestionAboutLeasesThatEndToTheExpiringLeasesWithTheWindow(String question, int days) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("find_expiring_leases");
        assertThat(call.input().get("within_days").asInt()).isEqualTo(days);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ما العقود التي تنتهي خلال 60 يومًا؟ | 60
            ما العقود التي تنتهي خلال الـ 90 يوم القادمة؟ | 90
            ما العقود المنتهية خلال ٤٥ يوما؟ | 45
            العقود التي تنتهي خلال شهرين | 60
            ما العقود التي تنتهي خلال 3 أشهر؟ | 90
            ما العقود التي تنتهي خلال أسبوعين؟ | 14
            ما العقود التي تنتهي الشهر القادم؟ | 30
            أي عقود تنتهي هذا الشهر؟ | 30
            ما الوحدات التي ينتهي عقدها خلال 30 يوما؟ | 30
            """)
    void routesAnArabicQuestionAboutLeasesThatEnd(String question, int days) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("find_expiring_leases");
        assertThat(call.input().get("within_days").asInt()).isEqualTo(days);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Which leases are expiring soon?", "Which leases expire?", "ما العقود التي تنتهي قريبًا؟",
            "Which units have an expiring lease?", "Which tenants have a lease expiring soon?",
            "ما المستأجرون الذين ينتهي عقدهم قريبًا؟"})
    void withoutAWindowTheToolChoosesItsDefault(String question) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("find_expiring_leases");
        assertThat(call.input().has("within_days")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What happens when the lease expires?",
            "How much notice must be given before the lease ends?",
            "When does the lease of unit A-203 end?",
            "Which unit has a lease ending soon, like A-203?",
            "ما الوحدات المرتبطة بعقد الوحدة A-203؟"})
    void aQuestionAboutOneLeaseThatMentionsItsEndIsNotAListOfLeases(String question) {
        var response = ask(List.of(Message.user(question)));

        assertThat(response.toolUses()).extracting(ContentBlock.ToolUse::name)
                .doesNotContain("find_expiring_leases");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Which leases require 90 days notice before they end?",
            "Which leases need 30 days written notice to end the contract?",
            "Which leases require a month's notice before they expire?",
            "Which leases require notice within 30 days of ending?",
            "Which leases require a notice period of 2 months?",
            "ما العقود التي تتطلب إشعارا قبل 90 يوما من انتهائها؟"})
    void aNoticePeriodIsALeaseTermNotTheWindowOfLeasesThatEnd(String question) {
        var call = route(question);

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("query").asText()).containsAnyOf("notice period", "مدة الإشعار");
        assertThat(call.input().has("unit_code")).isFalse();
    }

    @Test
    void theLengthOfALeaseIsNotWhenItEnds() {
        var response = ask(List.of(Message.user("Which leases end after 12 months?")));

        assertThat(response.toolUses()).extracting(ContentBlock.ToolUse::name).doesNotContain("find_expiring_leases");
    }

    @Test
    void aLeaseThatNeedsRenewalEndsSoonButRenewalNoticeIsTheRenewalClause() {
        var renewal = route("Which leases need renewal in the next 60 days?");
        var notice = route("Which leases need renewal notice?");

        assertThat(renewal.name()).isEqualTo("find_expiring_leases");
        assertThat(notice.name()).isEqualTo("search_lease_documents");
        assertThat(notice.input().get("query").asText()).contains("notice period", "term renewal");
    }

    @Test
    void aListOfLeasesThatNeedRenewalIsNotSearchedForTheRenewalClauseAfterwards() {
        var response = afterListing("Which leases need renewal in the next 60 days?", listing(2, 2));

        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.text()).contains("2 lease(s) end within the next 60 days");
    }

    @Test
    void theNoticeADocumentRequiresIsNotTheWindowOfTheQuestion() {
        var call = route("Which leases end in the next 60 days and require 90 days notice?");

        assertThat(call.name()).isEqualTo("find_expiring_leases");
        assertThat(call.input().get("within_days").asInt()).isEqualTo(60);
    }

    private ContentBlock.ToolResult listed(String json) {
        return new ContentBlock.ToolResult("t1", json, false);
    }

    private LlmResponse afterListing(String question, String json) {
        var call = new ContentBlock.ToolUse("t1", "find_expiring_leases", JSON.createObjectNode());
        return ask(List.of(Message.user(question), new Message(Message.Role.ASSISTANT, List.of(call)),
                new Message(Message.Role.USER, List.of(listed(json)))));
    }

    private static String listing(int leases, int shown) {
        var entries = IntStream.range(0, shown).mapToObj(i -> """
                {"unitCode":"A-%d","tenantNameEn":"Tenant %d","tenantNameAr":"مستأجر %d","endDate":"2026-12-%02d",
                 "daysLeft":%d,"monthlyRent":4800,"hasLeaseDocument":true}""".formatted(100 + i, i, i, i + 1, 14 + i))
                .toList();
        return "{\"withinDays\":60,\"totalLeases\":" + leases + ",\"shown\":" + shown + ",\"leases\":["
                + String.join(",", entries) + "]}";
    }

    @Test
    void aCombinedQuestionSearchesTheDocumentsOfTheLeasesItListedBeforeItAnswers() {
        var question = "Which leases end in the next 60 days and require 90 days notice?";

        var response = afterListing(question, listing(3, 3));

        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        var search = response.toolUses().get(0);
        assertThat(search.name()).isEqualTo("search_lease_documents");
        assertThat(texts(search.input().get("unit_codes"))).containsExactly("A-100", "A-101", "A-102");
        assertThat(search.input().get("query").asText()).contains("notice period");
        assertThat(search.input().get("top_k").asInt()).isEqualTo(8);
    }

    @Test
    void theSearchOfACombinedQuestionNamesAtMostTenUnitsAndOneUnitAsAUnitCode() {
        var question = "Which leases end in the next 60 days and what is the notice period?";

        var many = afterListing(question, listing(15, 15)).toolUses().get(0);
        var single = afterListing(question, listing(1, 1)).toolUses().get(0);

        assertThat(texts(many.input().get("unit_codes"))).hasSize(10).startsWith("A-100").endsWith("A-109");
        assertThat(single.input().get("unit_code").asText()).isEqualTo("A-100");
        assertThat(single.input().has("unit_codes")).isFalse();
    }

    @Test
    void anArabicCombinedQuestionIsFollowedUpToo() {
        var response = afterListing("ما العقود التي تنتهي خلال 60 يومًا وتتطلب مدة إشعار؟", listing(2, 2));

        var search = response.toolUses().get(0);
        assertThat(search.name()).isEqualTo("search_lease_documents");
        assertThat(search.input().get("query").asText()).contains("مدة الإشعار");
        assertThat(texts(search.input().get("unit_codes"))).containsExactly("A-100", "A-101");
    }

    @Test
    void aListOfLeasesThatEndIsAnsweredAtOnceWhenTheQuestionNamesNoLeaseTerm() {
        var response = afterListing("Which leases end in the next 60 days?", listing(2, 2));

        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.toolUses()).isEmpty();
        assertThat(response.text()).contains("2 lease(s) end within the next 60 days", "A-100", "A-101");
    }

    @Test
    void aCombinedQuestionWhoseListIsEmptySearchesNothing() {
        var response = afterListing("Which leases end in the next 60 days and require 90 days notice?",
                "{\"withinDays\":60,\"totalLeases\":0,\"shown\":0,\"leases\":[]}");

        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.text()).isEqualTo("No active lease ends within the next 60 days.");
    }

    @Test
    void aFailedListIsPassedOnAndNothingIsSearched() {
        var call = new ContentBlock.ToolUse("t1", "find_expiring_leases", JSON.createObjectNode());
        var error = new ContentBlock.ToolResult("t1", "within_days must be between 1 and 365", true);

        var response = ask(List.of(Message.user("Which leases end in the next 60 days and require notice?"),
                new Message(Message.Role.ASSISTANT, List.of(call)), new Message(Message.Role.USER, List.of(error))));

        assertThat(response.toolUses()).isEmpty();
        assertThat(response.text()).contains("I could not complete that: within_days must be between 1 and 365");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Which units in Riyadh are vacant right now? | search_units
            Who is more than 30 days late on rent? | find_overdue_tenants
            Which tenants have late payments? | find_overdue_tenants
            Who is behind on rent in unit A-203? | find_overdue_tenants
            Give me the details for unit A-203. | get_unit_details
            When does the lease of unit A-203 end? | get_unit_details
            Show the payment history for unit B-203. | get_payment_history
            Show the payments of unit A-203 | get_payment_history
            Draft a polite rent reminder in Arabic for tenant Ahmed in unit B-203. | draft_tenant_message
            Draft a lease renewal message for tenant Ahmed in unit A-203 | draft_tenant_message
            Write a message about the lease renewal to unit A-203 | draft_tenant_message
            Send a lease renewal notice to unit B-101 | send_tenant_reminder
            Which units are in maintenance? | search_units
            Summarise occupancy and collected rent for Building A this month. | get_occupancy_summary
            من المستأجرين المتأخرين في الدفع؟ | find_overdue_tenants
            ما سجل المدفوعات للوحدة B-203؟ | get_payment_history
            صغ رسالة تذكير بالإيجار بالعربية للوحدة B-203. | draft_tenant_message
            ما الوحدات الشاغرة في الرياض؟ | search_units
            """)
    void questionsThatAreNotAboutWhatALeaseSaysKeepTheirTools(String question, String tool) {
        assertThat(route(question).name()).isEqualTo(tool);
    }

    @Test
    void everyQuestionOfTheEvalsStillGoesToTheToolItExpects() throws Exception {
        var file = Path.of("..", "evals", "questions.json");
        Assumptions.assumeTrue(Files.exists(file), "the evals are not next to the backend");
        var questions = JSON.readTree(file.toFile());

        assertThat(questions.size()).isGreaterThanOrEqualTo(26);
        for (var question : questions) {
            var text = question.get("question").asText();
            var expected = new ArrayList<String>();
            question.get("tools").forEach(tool -> expected.add(tool.asText()));
            var response = ask(List.of(Message.user(text)));
            assertThat(response.toolUses()).as("%s: %s", question.get("id").asText(), text).isNotEmpty();
            assertThat(expected).as("%s: %s", question.get("id").asText(), text)
                    .contains(response.toolUses().get(0).name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"notice ", "renew ", "leases ", "leases ending in the next ", "penalty, ", "عقود ",
            "غرامة ", "tenant A", "pets pets pets ", "lease ", "ـ", "responsible ", "who pays for ", "maintenance ",
            "obligations of maintenance ", "مسؤول ", "من يدفع ", "الصيانة ", "التزامات الصيانة ", "وللحيوانات ",
            "which units have a lease ", "needs renewal ", "90 days notice ", "notice of 3 ", "a month's ",
            "اشعارا قبل ", "90 يوما اشعار ", "اشعار ", "الوحدات التي ينتهي عقدها "})
    void aLongHostileQuestionIsRoutedQuickly(String unit) {
        var hostile = unit.repeat(20_000 / unit.length());

        var response = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> ask(List.of(Message.user(hostile))));

        assertThat(response.stopReason()).isIn(StopReason.TOOL_USE, StopReason.END_TURN);
        response.toolUses().forEach(use -> {
            var query = use.input().path("query").asText("");
            assertThat(query.length()).isLessThanOrEqualTo(500);
        });
    }

    @Test
    void aLongQuestionThatNamesEveryTermAndWindowIsRoutedQuickly() {
        var hostile = ("Which leases end in the next 60 days: late fee, notice period, renewal, repairs, pets, "
                + "early termination, deposit, sublet, inspection, house rules, governing law, special conditions "
                + "for tenant Ahmed Al-Harbi in unit A-203, B-101, C-105. ").repeat(150);

        var response = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> ask(List.of(Message.user(hostile))));

        assertThat(response.toolUses()).extracting(ContentBlock.ToolUse::name).containsExactly("find_expiring_leases");
    }

    @Test
    void longRunsOfWhiteSpaceAroundTheNumberOfAnArabicWindowAreReadInTimeProportionalToTheirLength() {
        var spaces = " ".repeat(4_000);
        var unreadable = "عقود تنتهي في" + spaces + "1" + spaces + "x";
        var dual = "عقود تنتهي في" + spaces + "ال" + spaces + "x";
        var padded = "عقود تنتهي خلال" + spaces + "ال" + spaces + "90" + spaces + "ال" + spaces + "يوم";

        var responses = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> List.of(
                route(unreadable), route(dual), route(padded)));

        assertThat(responses).extracting(ContentBlock.ToolUse::name).containsOnly("find_expiring_leases");
        assertThat(responses.get(0).input().has("within_days")).isFalse();
        assertThat(responses.get(1).input().has("within_days")).isFalse();
        assertThat(responses.get(2).input().get("within_days").asInt()).isEqualTo(90);
    }

    @Test
    void aVeryLongQuestionIsCutToWhatTheToolTakes() {
        var question = "What does the lease agreement say? " + "x".repeat(20_000);

        var call = route(question);

        assertThat(call.name()).isEqualTo("search_lease_documents");
        assertThat(call.input().get("query").asText().length()).isLessThanOrEqualTo(500);
    }
}
