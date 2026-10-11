package dev.proppilot.agent.llm.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TenantNameTest {

    private static String name(String question) {
        return TenantName.in(question).orElse(null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            What is the notice period for tenant Ahmed Al-Harbi? | Ahmed Al-Harbi
            what is the late fee for tenant ahmed al-harbi? | ahmed al-harbi
            notice period for the tenant sara al-qahtani | sara al-qahtani
            are pets allowed for tenant sara? | sara
            What is the late fee for tenant Ahmed's lease? | Ahmed
            Is subletting allowed in tenant Ahmed’s lease? | Ahmed
            What is the late fee of resident Khalid Al Otaibi in the lease? | Khalid Al-Otaibi
            Does the tenant named Noura Al-Zahrani have to give notice? | Noura Al-Zahrani
            What does the tenant Ahmed pay for repairs? | Ahmed
            late fee for lessee Mohammed bin Salman, please | Mohammed bin Salman
            """)
    void readsTheNameAfterTheWordTenantInAnyCase(String question, String expected) {
        assertThat(name(question)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            What is the late fee for Ahmed Al-Harbi? | Ahmed Al-Harbi
            What is the late fee in Sara Al-Qahtani's lease? | Sara Al-Qahtani
            Show Ahmed Al-Harbi's notice period | Ahmed Al-Harbi
            Ahmed's late fee? | Ahmed
            Hello. Does Sara have to give notice? | Sara
            Is a pet allowed for Noura? | Noura
            """)
    void readsARunOfCapitalisedWordsWhenThereIsNoWordTenant(String question, String expected) {
        assertThat(name(question)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is the notice period?",
            "What notice must the tenant give?",
            "What does the tenant have to pay for repairs?",
            "Can the tenant sublet the apartment?",
            "Is subletting allowed for the tenant in unit A-203?",
            "What is the tenant's notice period?",
            "What is the Late Fee?",
            "What is the Notice Period for the Security Deposit?",
            "What is the late fee in Riyadh?",
            "Compare the notice period in Najd Towers and Red Sea Court",
            "Which leases end in January or in Building A?",
            "Notice period?",
            "What is the notice period in SAR?",
            "ما مدة الإشعار؟",
            "ما الذي يجب أن يدفعه المستأجر؟",
            "هل يجوز للمستأجر في الوحدة التأجير من الباطن؟"})
    void readsNoNameFromAQuestionThatNamesNoTenant(String question) {
        assertThat(name(question)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ما مدة الإشعار للمستأجر أحمد؟ | أحمد
            ما مدة الإشعار للمستأجر أحمد الحربي؟ | أحمد الحربي
            ما غرامة التأخير على المستأجر عبدالله بن سعود في عقده؟ | عبدالله بن سعود
            ما غرامة التأخير للمستأجرة سارة القحطاني | سارة القحطاني
            ما غرامة التأخير للمستأجر أَحْمَد في العقد؟ | أحمد
            """)
    void readsAnArabicNameAfterTheTenantWithItsFamilyName(String question, String expected) {
        assertThat(name(question)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant ", "tenant A", "The tenant Ahmed's ", "Ahmed Al-Harbi ", "المستأجر أحمد ",
            "tenant ahmed al-harbi's ", "Al Al Al ", "A. ", "'s ", "tenant tenant tenant ", "المستأجر الحربي الحربي "})
    void aLongHostileQuestionIsReadQuickly(String unit) {
        var hostile = unit.repeat(20_000 / unit.length());

        var result = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> TenantName.in(hostile));

        result.ifPresent(found -> assertThat(found.length()).isLessThanOrEqualTo(100));
    }
}
