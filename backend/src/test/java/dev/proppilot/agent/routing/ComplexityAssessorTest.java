package dev.proppilot.agent.routing;

import static dev.proppilot.agent.routing.ComplexityRule.LONG_CONVERSATION;
import static dev.proppilot.agent.routing.ComplexityRule.LONG_QUESTION;
import static dev.proppilot.agent.routing.ComplexityRule.MANY_UNITS;
import static dev.proppilot.agent.routing.ComplexityRule.SEQUENCING_WORD;
import static dev.proppilot.agent.routing.ComplexityRule.SEVERAL_DATA_AREAS;
import static dev.proppilot.agent.routing.ComplexityRule.WINDOW_WITH_LEASE_TERM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ComplexityAssessorTest {

    private final ComplexityAssessor assessor = new ComplexityAssessor();

    private Complexity assess(String question) {
        return assessor.assess(question, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Which units in Riyadh are vacant right now?",
            "Who is more than 30 days late on rent?",
            "Give me the details for unit A-203.",
            "Who lives in unit B-101 and what is the rent?",
            "Show the payment history for unit B-203.",
            "Draft a polite rent reminder in Arabic for tenant Ahmed in unit B-203.",
            "Record a payment of 4950 SAR for unit A-504.",
            "Create a maintenance request for unit A-504, plumbing, high priority, leaking pipe.",
            "Send a rent reminder to unit B-203.",
            "What is the late fee in the lease for unit A-203?",
            "What is the late payment fee in the lease for unit A-203?",
            "What is the late fee in Sara Al-Qahtani's lease?",
            "What notice period does the lease for unit B-501 require?",
            "Are pets allowed in unit B-201?",
            "What is the early termination fee for unit B-102?",
            "What do the special conditions say in the lease for unit A-301?",
            "Which leases end in the next 60 days?",
            "ما الوحدات الشاغرة في الرياض؟",
            "من المستأجرين المتأخرين في الدفع؟",
            "ما تفاصيل الوحدة B-101؟",
            "ما سجل المدفوعات للوحدة B-203؟",
            "صغ رسالة تذكير بالإيجار بالعربية للوحدة B-203.",
            "سجّل دفعة بمبلغ 4950 ريال للوحدة A-504.",
            "أنشئ طلب صيانة للوحدة A-504، سباكة، أولوية عالية، تسرب في الماسورة.",
            "أرسل تذكيرًا بالإيجار للوحدة B-203.",
            "ما غرامة التأخير في عقد الوحدة B-203؟",
            "ما الشروط الخاصة في عقد الوحدة A-301؟"})
    void aQuestionThatTakesOneToolIsNotScored(String question) {
        assertThat(assess(question).rules()).isEmpty();
    }

    @Test
    void aSummaryOfTwoThingsInOneToolScoresOnePointAndStaysBelowTheThreshold() {
        var english = assess("Summarise occupancy and collected rent for Building A this month.");
        var arabic = assess("لخّص الإشغال والإيجار المحصّل للمبنى A هذا الشهر.");

        assertThat(english.rules()).containsExactly(SEQUENCING_WORD);
        assertThat(arabic.rules()).containsExactly(SEQUENCING_WORD);
    }

    @Test
    void aQuestionAcrossTwoDataAreasScoresTwoPoints() {
        var complexity = assess("Which units are vacant and who is overdue on rent?");

        assertThat(complexity.rules()).containsExactly(SEVERAL_DATA_AREAS);
        assertThat(complexity.score()).isEqualTo(2);
    }

    @Test
    void theAreasAreCountedInArabicToo() {
        var complexity = assess("ما الوحدات الشاغرة ومن هم المستأجرون المتأخرون؟");

        assertThat(complexity.rules()).containsExactly(SEVERAL_DATA_AREAS);
    }

    @Test
    void aLeaseTermAndALeaseThatEndsAreTwoAreas() {
        var complexity = assess("Which leases expire soon and what does the lease say about pets?");

        assertThat(complexity.rules()).containsExactly(SEVERAL_DATA_AREAS);
    }

    @Test
    void whatFollowsTheObjectOfACommandIsWhatItIsFor() {
        assertThat(assess("Send a reminder about the overdue rent of A-504").rules()).isEmpty();
        assertThat(assess("Record a payment of 500 SAR for the invoice that is outstanding").rules()).isEmpty();
    }

    @Test
    void aCommandEndsAtTheNextAnd() {
        var complexity = assess("Send a reminder to A-504 and show the vacant units");

        assertThat(complexity.rules()).containsExactly(SEVERAL_DATA_AREAS);
    }

    @Test
    void aMessageToDraftAndAChangeToMakeAreTwoAreas() {
        var complexity = assess("Draft a message to the tenant of A-203 and record a payment of 500 SAR");

        assertThat(complexity.rules()).containsExactly(SEVERAL_DATA_AREAS);
    }

    @Test
    void theWordsOfACommandAreNotAQuestionAboutPaymentsToo() {
        assertThat(assess("Record a payment of 4950 SAR for unit A-504").rules()).isEmpty();
        assertThat(assess("Show the record of the payments of A-504").rules()).isEmpty();
        assertThat(assess("Add up the payments of A-504").rules()).isEmpty();
    }

    @Test
    void aWindowWithALeaseTermScoresFourWithTheAreasItReachesInto() {
        var english = assess("Which leases end in the next 60 days and require 90 days notice?");
        var arabic = assess("ما العقود التي تنتهي خلال 60 يومًا وتتطلب إشعارًا مدته 90 يومًا؟");

        assertThat(english.rules()).containsExactly(SEVERAL_DATA_AREAS, WINDOW_WITH_LEASE_TERM);
        assertThat(english.score()).isEqualTo(4);
        assertThat(arabic.rules()).containsExactly(SEVERAL_DATA_AREAS, WINDOW_WITH_LEASE_TERM);
        assertThat(arabic.score()).isEqualTo(4);
    }

    @Test
    void aWindowAloneOrALeaseTermAloneIsNotTheCombination() {
        assertThat(assess("Which leases end within two months?").rules()).doesNotContain(WINDOW_WITH_LEASE_TERM);
        assertThat(assess("What notice period does the lease for B-501 need?").rules())
                .doesNotContain(WINDOW_WITH_LEASE_TERM);
    }

    @Test
    void aQuestionOfMoreThan160CharactersScoresOnePoint() {
        assertThat(assess("x".repeat(160)).rules()).isEmpty();
        assertThat(assess("x".repeat(161)).rules()).containsExactly(LONG_QUESTION);
        assertThat(assess("  " + "x".repeat(160) + "  ").rules()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Show the vacant units and then the overdue tenants",
            "Check the vacant units, then draft a note",
            "How many units are there in each building?",
            "Compare the occupancy of building A and building B",
            "Which of the two, both A-101 and B-202, are vacant?",
            "Summarise the occupancy and the overdue rent",
            "Look up the late fee and after that the notice period",
            "لخّص الإشغال والمتأخرات",
            "كم عدد الوحدات الشاغرة لكل مبنى؟",
            "قارن الإشغال بين المبنى A والمبنى B",
            "اعرض الوحدات الشاغرة ثم المستأجرين المتأخرين",
            "اعرض الوحدات الشاغرة وثم المتأخرين",
            "كلاهما شاغر؟"})
    void aWordOfSequenceOrAggregationScoresOnePoint(String question) {
        assertThat(assess(question).rules()).contains(SEQUENCING_WORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Summarise occupancy",
            "Show the vacant units and the overdue tenants",
            "What is the status of the thenar clinic",
            "لخص وحدات الرياض",
            "اعرض الوحدات الشاغرة"})
    void withoutAWordOfSequenceOrAggregationNothingIsAdded(String question) {
        assertThat(assess(question).rules()).doesNotContain(SEQUENCING_WORD);
    }

    @Test
    void threeOrMoreDifferentUnitsScoreOnePoint() {
        assertThat(assess("Rent of A-101, B-202 and C-303").rules()).containsExactly(MANY_UNITS);
        assertThat(assess("Rent of A-101 and B-202").rules()).isEmpty();
    }

    @Test
    void theSameUnitSaidTwiceOrInAnotherSpellingIsOneUnit() {
        assertThat(assess("A-101 then a101, and A-101 again, and B-202").rules()).containsExactly(SEQUENCING_WORD);
    }

    @Test
    void aLeaseDocumentCodeNamesItsUnit() {
        assertThat(assess("Compare LSE-A-101 with A-101").rules()).containsExactly(SEQUENCING_WORD);
    }

    @Test
    void unitsAreCountedInArabicToo() {
        assertThat(assess("سجل الإيجار للوحدات A-101 و B-202 و C-303").rules()).contains(MANY_UNITS);
    }

    @Test
    void aLongConversationScoresOnePointFromTheSixthEarlierMessage() {
        assertThat(assessor.assess("And the next one?", 5).rules()).isEmpty();
        assertThat(assessor.assess("And the next one?", 6).rules()).containsExactly(LONG_CONVERSATION);
    }

    @Test
    void theScoreIsTheSumOfThePointsOfTheRulesMet() {
        var question = "Compare the late fee of A-101, B-202 and C-303 and then list the leases that end in the next "
                + "30 days with the vacant units of Building A, and tell me which tenants are overdue on rent";

        var complexity = assessor.assess(question, 8);

        assertThat(complexity.rules()).containsExactly(SEVERAL_DATA_AREAS, WINDOW_WITH_LEASE_TERM, LONG_QUESTION,
                SEQUENCING_WORD, MANY_UNITS, LONG_CONVERSATION);
        assertThat(complexity.score()).isEqualTo(2 + 2 + 1 + 1 + 1 + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"and then ", ", , , ", "a ", "و", "A-203 ", "(((", "ثم ", "late payment fee of ",
            "record a payment of ", "ـ", "9", "\u0000", "😀"})
    void aHostileQuestionOfAHundredThousandCharactersIsReadInBoundedTime(String unit) {
        var hostile = unit.repeat(100_000 / unit.length());

        var complexity = assertTimeout(Duration.ofSeconds(5), () -> assess(hostile));

        assertThat(complexity.rules()).contains(LONG_QUESTION);
    }

    @Test
    void aSingleUnbrokenWordOfAMillionCharactersIsReadInBoundedTime() {
        var complexity = assertTimeout(Duration.ofSeconds(5), () -> assess("ا".repeat(1_000_000)));

        assertThat(complexity.rules()).containsExactly(LONG_QUESTION);
    }

    @Test
    void onlyTheStartOfAQuestionIsRead() {
        var late = "x ".repeat(ComplexityAssessor.READ_CHARACTERS / 2) + "compare then each both";

        assertThat(assess(late).rules()).containsExactly(LONG_QUESTION);
    }
}
