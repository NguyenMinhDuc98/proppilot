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
    void turnsToolResultIntoAnswerThatNamesTheTool() {
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
        assertThat(response.text()).contains("Ahmed Al-Harbi", "A-203", "98", "Data: find_overdue_tenants");
    }
}
