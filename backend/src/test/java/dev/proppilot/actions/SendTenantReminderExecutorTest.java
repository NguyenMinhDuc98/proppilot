package dev.proppilot.actions;

import static dev.proppilot.actions.Fixtures.lease;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.agent.tools.MessageDrafter;
import dev.proppilot.agent.tools.MessageDrafter.Drafted;
import dev.proppilot.agent.tools.MessageDrafter.Language;
import dev.proppilot.agent.tools.MessageDrafter.Purpose;
import dev.proppilot.domain.OutboxMessageRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SendTenantReminderExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LeaseTargets targets = mock(LeaseTargets.class);
    private final MessageDrafter drafter = mock(MessageDrafter.class);
    private final SendTenantReminderExecutor executor =
            new SendTenantReminderExecutor(targets, drafter, mock(OutboxMessageRepository.class), JSON);

    @BeforeEach
    void aLeaseOfATenantWhoPrefersEnglish() {
        when(targets.resolve(any())).thenReturn(lease("A-203", LocalDate.of(2025, 11, 1)));
    }

    private void drafterAnswers(Drafted answer) {
        when(drafter.draft(any(), any(), any())).thenReturn(answer);
    }

    @Test
    void asksTheDrafterForTheChosenLanguageAndStoresItExplicitly() {
        drafterAnswers(new Drafted.Message("Ahmed Al-Harbi", "أحمد الحربي", "A-203", Language.AR, Purpose.LEASE_RENEWAL, "s", "b"));

        var validated = executor.validate(JSON.createObjectNode().put("unit_code", "A-203")
                .put("purpose", "lease_renewal").put("language", "ar"));

        assertThat(validated.language()).isEqualTo(Language.AR);
        assertThat(executor.arguments(validated).toString())
                .contains("\"unit_code\":\"A-203\"", "\"purpose\":\"LEASE_RENEWAL\"", "\"language\":\"AR\"");
    }

    @Test
    void usesTheTenantsPreferredLanguageWhenNoneIsGiven() {
        drafterAnswers(new Drafted.Message("Ahmed Al-Harbi", "أحمد الحربي", "A-203", Language.EN, Purpose.RENT_REMINDER, "s", "b"));
        var args = JSON.createObjectNode().put("unit_code", "A-203").put("purpose", "RENT_REMINDER");

        assertThat(executor.validate(args).language()).isEqualTo(Language.EN);

        var arabicSpeaker = lease("A-203", LocalDate.of(2025, 11, 1));
        ReflectionTestUtils.setField(arabicSpeaker.getTenant(), "preferredLanguage", "ar");
        when(targets.resolve(any())).thenReturn(arabicSpeaker);
        assertThat(executor.validate(args).language()).isEqualTo(Language.AR);
    }

    @Test
    void aRentReminderWithoutOverdueRentIsRefusedWithItsOwnCode() {
        drafterAnswers(new Drafted.NoOverdueRent("A-203"));

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(ActionException.class,
                () -> executor.validate(JSON.createObjectNode().put("unit_code", "A-203").put("purpose", "RENT_REMINDER")));

        assertThat(thrown.error()).isEqualTo(ActionError.NO_OVERDUE_RENT);
        assertThat(thrown.getMessage()).isEqualTo("Unit A-203 has no overdue rent, so no reminder is needed.");
    }

    @Test
    void summaryTellsThatNothingIsSent() {
        drafterAnswers(new Drafted.Message("Ahmed Al-Harbi", "أحمد الحربي", "A-203", Language.EN, Purpose.MAINTENANCE_NOTICE, "s", "b"));
        var validated = executor.validate(JSON.createObjectNode().put("unit_code", "A-203").put("purpose", "MAINTENANCE_NOTICE"));

        var summary = executor.summary(validated);

        assertThat(summary.en()).isEqualTo(
                "Queue a maintenance notice in English for Ahmed Al-Harbi (unit A-203). It is stored in the outbox; nothing is sent.");
        assertThat(summary.ar()).contains("إشعار بالصيانة").contains("الإنجليزية").contains("أحمد الحربي").contains("لا يُرسل أي شيء");
    }
}
