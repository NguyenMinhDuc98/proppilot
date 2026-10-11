package dev.proppilot.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.llm.offline.OfflineLlmClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OfflineLlmClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final OfflineLlmClient client = new OfflineLlmClient(new ObjectMapper());

    private ContentBlock.ToolUse route(String question) {
        var response = client.complete(new LlmRequest("", List.of(Message.user(question)), List.of(), TIMEOUT), s -> { });
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        return response.toolUses().get(0);
    }

    @Test
    void theExamplesItOffersForAQuestionItDoesNotUnderstandAreItsOwnTextForTheGuardrail() {
        var asked = client.complete(new LlmRequest("", List.of(Message.user("tell me a joke")), List.of(), TIMEOUT),
                s -> { });
        var asArabic = client.complete(new LlmRequest("", List.of(Message.user("احكِ لي نكتة")), List.of(), TIMEOUT),
                s -> { });

        assertThat(client.ownText()).contains(asked.text(), asArabic.text());
    }

    @Test
    void ignoresTheModelARequestNamesBecauseItHasOnlyOne() {
        var messages = List.of(Message.user("Show me unit B-101"));

        var plain = client.complete(new LlmRequest("", messages, List.of(), TIMEOUT), s -> { });
        var named = client.complete(new LlmRequest("", messages, List.of(), TIMEOUT, "claude-sonnet-4-5"), s -> { });

        assertThat(named.toolUses()).hasSize(1);
        assertThat(named.toolUses().get(0).name()).isEqualTo(plain.toolUses().get(0).name());
        assertThat(named.toolUses().get(0).input()).isEqualTo(plain.toolUses().get(0).input());
        assertThat(client.model()).isEqualTo("rule-based");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Which units in Riyadh are vacant right now? | search_units
            ما الوحدات الشاغرة في الرياض؟ | search_units
            Who is more than 30 days late on rent? | find_overdue_tenants
            من المستأجرين المتأخرين في الدفع؟ | find_overdue_tenants
            Summarise occupancy and collected rent for Building A this month | get_occupancy_summary
            ملخص الإشغال للمبنى A | get_occupancy_summary
            Draft a polite rent reminder in Arabic for tenant Ahmed in unit A-203 | draft_tenant_message
            Show me unit B-101 | get_unit_details
            Payment history for unit A-203 | get_payment_history
            """)
    void routesQuestionsToTheRightTool(String question, String expectedTool) {
        assertThat(route(question).name()).isEqualTo(expectedTool);
    }

    @Test
    void extractsFiltersFromTheQuestion() {
        var call = route("Show vacant 2 bedroom units in Jeddah building C");

        assertThat(call.input().get("city").asText()).isEqualTo("Jeddah");
        assertThat(call.input().get("building").asText()).isEqualTo("C");
        assertThat(call.input().get("bedrooms").asInt()).isEqualTo(2);
        assertThat(call.input().get("status").asText()).isEqualTo("VACANT");
    }

    @Test
    void extractsDayThreshold() {
        assertThat(route("tenants more than 45 days overdue").input().get("min_days_overdue").asInt()).isEqualTo(45);
    }

    @Test
    void aDayThresholdTooBigForAnIntegerIsKeptWithinWhatTheToolAccepts() {
        for (var days : new String[] {"3650", "3651", "99999", "2147483648", "99999999999999999999",
                "9".repeat(480)}) {
            var call = route("tenants more than " + days + " days overdue");

            assertThat(call.name()).isEqualTo("find_overdue_tenants");
            assertThat(call.input().get("min_days_overdue").asInt()).as(days.length() + " digits")
                    .isEqualTo(Math.min(3650, days.length() > 5 ? 3650 : Integer.parseInt(days)));
        }
    }

    @Test
    void aBareNumberTooBigForAnIntegerIsKeptWithinWhatTheToolAccepts() {
        var call = route("who is overdue by 99999999999999999999");

        assertThat(call.name()).isEqualTo("find_overdue_tenants");
        assertThat(call.input().get("min_days_overdue").asInt()).isEqualTo(3650);
    }

    @Test
    void draftInArabicRequestUsesArabicLanguageAndUnitCode() {
        var call = route("Draft a polite rent reminder in Arabic for tenant Ahmed in unit A-203");

        assertThat(call.input().get("unit_code").asText()).isEqualTo("A-203");
        assertThat(call.input().get("language").asText()).isEqualTo("AR");
        assertThat(call.input().get("purpose").asText()).isEqualTo("RENT_REMINDER");
    }

    @Test
    void unknownQuestionGetsHelpTextInTheQuestionsLanguage() {
        var english = client.complete(new LlmRequest("", List.of(Message.user("what is the weather")), List.of(), TIMEOUT), s -> { });
        var arabic = client.complete(new LlmRequest("", List.of(Message.user("كيف الطقس")), List.of(), TIMEOUT), s -> { });

        assertThat(english.toolUses()).isEmpty();
        assertThat(english.text()).contains("Try:");
        assertThat(arabic.text()).contains("جرّب");
    }

    @Test
    void turnsToolResultIntoAnswerWithoutNamingTheTool() {
        var call = new ContentBlock.ToolUse("t1", "find_overdue_tenants", new ObjectMapper().createObjectNode());
        var result = new ContentBlock.ToolResult("t1", """
                {"minDaysOverdue":30,"totalTenants":1,"shown":1,"totalUnpaidAmount":9400,
                 "tenants":[{"nameEn":"Ahmed Al-Harbi","nameAr":"أحمد الحربي","unitCode":"A-203","unpaidMonths":4,"unpaidAmount":9400,"daysOverdue":98}]}""", false);
        var messages = List.of(
                Message.user("Who is late?"),
                new Message(Message.Role.ASSISTANT, List.of(call)),
                new Message(Message.Role.USER, List.of(result)));

        var response = client.complete(new LlmRequest("", messages, List.of(), TIMEOUT), s -> { });

        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.text()).contains("Ahmed Al-Harbi", "A-203", "98")
                .doesNotContain("find_overdue_tenants").doesNotContain("Data:");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Payment history for unit A-203 | Partially paid | Remaining (SAR)
            سجل الدفعات للوحدة A-203 | مدفوع جزئيًا | المتبقي (ريال)
            """)
    void paymentHistoryShowsPartlyPaidMonthsWithTheAmountLeft(String question, String statusLabel, String remainingHeader) {
        var call = new ContentBlock.ToolUse("t1", "get_payment_history", new ObjectMapper().createObjectNode());
        var result = new ContentBlock.ToolResult("t1", """
                {"tenantEn":"Ahmed Al-Harbi","tenantAr":"أحمد الحربي","unitCode":"A-203","unpaidMonths":1,
                 "payments":[{"month":"2026-10","amount":4800,"paidAmount":1000,"remainingAmount":3800,
                              "paidOn":null,"status":"PARTIAL","daysLate":9}]}""", false);
        var messages = List.of(
                Message.user(question),
                new Message(Message.Role.ASSISTANT, List.of(call)),
                new Message(Message.Role.USER, List.of(result)));

        var response = client.complete(new LlmRequest("", messages, List.of(), TIMEOUT), s -> { });

        assertThat(response.text()).contains(remainingHeader).contains("| 2026-10 | " + statusLabel + " | 4800 | 3800 |");
    }

    @Test
    void anAmountWithCentsIsShownWithBothCentDigits() {
        var call = new ContentBlock.ToolUse("t1", "get_payment_history", new ObjectMapper().createObjectNode());
        var result = new ContentBlock.ToolResult("t1", """
                {"tenantEn":"Ahmed Al-Harbi","tenantAr":"أحمد الحربي","unitCode":"A-203","unpaidMonths":1,
                 "payments":[{"month":"2026-10","amount":4800,"paidAmount":1000.5,"remainingAmount":3799.5,
                              "paidOn":null,"status":"PARTIAL","daysLate":9}]}""", false);
        var messages = List.of(
                Message.user("Payment history for unit A-203"),
                new Message(Message.Role.ASSISTANT, List.of(call)),
                new Message(Message.Role.USER, List.of(result)));

        var response = client.complete(new LlmRequest("", messages, List.of(), TIMEOUT), s -> { });

        assertThat(response.text()).contains("| 2026-10 | Partially paid | 4800 | 3799.50 |");
    }
}
