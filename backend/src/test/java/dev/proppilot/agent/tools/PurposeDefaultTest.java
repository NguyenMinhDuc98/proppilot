package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A "reminder" with no other purpose is a rent reminder. The model is told where it picks the purpose, so that it
 * drafts the message instead of asking which kind of reminder is meant.
 */
class PurposeDefaultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<JsonNode> SCHEMAS = List.of(
            new DraftTenantMessageTool(null, null, JSON).inputSchema(),
            new SendTenantReminderTool(null, JSON).inputSchema());

    @Test
    void bothMessageToolsSayThatAReminderWithNoOtherPurposeIsARentReminder() {
        assertThat(SCHEMAS).allSatisfy(schema -> assertThat(schema.at("/properties/purpose/description").asText())
                .contains("A reminder, or a message with no other purpose, is RENT_REMINDER"));
    }

    @Test
    void thePurposeIsStillOneOfTheThreeAndStillGivenByTheModel() {
        assertThat(SCHEMAS).allSatisfy(schema -> {
            assertThat(schema.at("/properties/purpose/enum")).extracting(JsonNode::asText)
                    .containsExactly("RENT_REMINDER", "LEASE_RENEWAL", "MAINTENANCE_NOTICE");
            assertThat(schema.get("required")).extracting(JsonNode::asText).contains("purpose");
        });
    }
}
