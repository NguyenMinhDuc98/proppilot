package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What an answer may state, given what a run was told: figures, unit codes, tenant names and citations. */
class GroundingCheckerTest {

    private static final TenantNames.Name AHMED = new TenantNames.Name("Ahmed Al-Harbi", "أحمد الحربي");
    private static final TenantNames.Name SARA = new TenantNames.Name("Sara Al-Qahtani", "سارة القحطاني");

    private final GroundingChecker checker = new GroundingChecker(new TenantNames(() -> List.of(AHMED, SARA)));

    private static Evidence evidence(String question, String... toolResults) {
        return new Evidence(List.of(toolResults), List.of(question), Set.of());
    }

    /** The answer to a question that states nothing the answer could lean on. */
    private List<Violation> check(String answer, String... toolResults) {
        return checkAsked("Tell me about the portfolio", answer, toolResults);
    }

    private List<Violation> checkAsked(String question, String answer, String... toolResults) {
        return checker.check(answer, evidence(question, toolResults));
    }

    private static List<String> values(List<Violation> violations) {
        return violations.stream().map(Violation::value).toList();
    }

    private static String arrayOf(int length) {
        return IntStream.range(0, length).mapToObj(i -> "{\"code\":\"x\"}").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void anAnswerThatStatesNothingIsGrounded() {
        assertThat(check("I could not find a lease document for that unit.")).isEmpty();
        assertThat(check("")).isEmpty();
    }

    @Test
    void aViolationNamesItsKindTheValueAsWrittenAndTheFormItWasComparedIn() {
        var violations = check("The rent is SAR 5,000.00 a month.", "{\"monthlyRent\":4950}");

        assertThat(violations).containsExactly(new Violation(ClaimKind.NUMBER, "5,000.00", "5000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"The rent is SAR 4,950 a month.", "The rent is 4950 SAR a month.",
            "The rent is 4,950.00 SAR a month.", "The rent is 4950.0 riyals.", "The rent is $4,950.",
            "الإيجار ٤٩٥٠ ريال شهريًا", "الإيجار ٤٬٩٥٠٫٠٠ ريال شهريًا", "الإيجار ۴۹۵۰ ريال شهريًا",
            "The rent is 04950."})
    void aFigureOfTheToolResultsIsGroundedWhateverItsFormat(String answer) {
        assertThat(check(answer, "{\"unit\":\"A-203\",\"monthlyRent\":4950.00}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"about 4,900", "roughly 5000", "4,950.5", "4,951", "4.95", "49,500"})
    void aRoundedOrDifferentFigureIsNotGrounded(String written) {
        var violations = check("The rent is " + written + " SAR.", "{\"monthlyRent\":4950.00}");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).kind()).isEqualTo(ClaimKind.NUMBER);
    }

    @Test
    void anAmountThatJacksonWroteWithAnExponentIsGroundedByItsDigits() {
        var result = "{\"monthlyRent\":4.8E+3,\"occupancyRatePercent\":8E+1,\"rentRoll\":1.33E+5,\"fee\":5E-1}";

        assertThat(check("The rent is 4,800 SAR; occupancy is 80%; the roll is 133,000; the fee is 0.5.", result))
                .isEmpty();
        // the digits of the mantissa and the exponent are not figures of their own
        assertThat(values(check("The rent is 4.8 SAR and 48,000 a year.", result))).containsExactly("4.8", "48,000");
    }

    @Test
    void aNumberWithAnExponentTooBigToBeAFigureIsNotExpanded() {
        var result = "{\"huge\":1E+999999999}";

        assertThat(values(check("The rent is 4,800 SAR.", result))).containsExactly("4,800");
    }

    @Test
    void aFigureInAStringOfAToolResultIsGrounded() {
        var result = "{\"note\":\"Late fee of 75 SAR per day after a grace period of 5 days\"}";

        assertThat(check("The late fee is 75 SAR a day.", result)).isEmpty();
        assertThat(values(check("The late fee is 76 SAR a day.", result))).containsExactly("76");
    }

    @Test
    void aPercentageIsGroundedByItsNumberAndNotByItsFraction() {
        var result = "{\"occupancyRatePercent\":\"80.0\",\"collectionRatePercent\":0.85}";

        assertThat(check("Occupancy is 80% this month.", result)).isEmpty();
        assertThat(check("الإشغال ٨٠٪ هذا الشهر", result)).isEmpty();
        assertThat(values(check("Occupancy is 80.5% this month.", result))).containsExactly("80.5");
        assertThat(values(check("Collection is 85%.", result))).containsExactly("85");
    }

    @Test
    void theNumbersOfADateAreGroundedByTheDateOfTheEvidence() {
        var result = "{\"endDate\":\"2026-03-15\"}";

        assertThat(check("The lease ends on 2026-03-15.", result)).isEmpty();
        assertThat(check("The lease ends on 15 March 2026.", result)).isEmpty();
        assertThat(values(check("The lease ends on 16 March 2026.", result))).containsExactly("16");
    }

    @Test
    void theLengthOfAnArrayGroundsACountThatNoNumberOfTheResultStates() {
        var result = "{\"units\":" + arrayOf(12) + "}";

        assertThat(check("There are 12 vacant units.", result)).isEmpty();
        assertThat(values(check("There are 13 vacant units.", result))).containsExactly("13");
    }

    @Test
    void anArrayInsideAnObjectInsideAnArrayIsCountedToo() {
        var result = "{\"buildings\":[{\"units\":" + arrayOf(25) + "},{\"units\":[]}]}";

        assertThat(check("Building A has 25 units.", result)).isEmpty();
    }

    @Test
    void aNumberUpToTenIsNeverReported() {
        assertThat(check("The top 3 are listed; 10 units are vacant and 7 are late.")).isEmpty();
        assertThat(values(check("11 units are vacant."))).containsExactly("11");
    }

    @Test
    void aDecimalBelowTenIsStillHeldAgainstTheEvidence() {
        assertThat(values(check("The unit has 2.5 bathrooms."))).containsExactly("2.5");
    }

    @Test
    void theNumberOfAListItemIsNoFigure() {
        var answer = IntStream.rangeClosed(1, 14).mapToObj(i -> i + ". An item").collect(Collectors.joining("\n"));

        assertThat(check(answer)).isEmpty();
    }

    @Test
    void aLongNumberAtTheStartOfALineIsAFigure() {
        assertThat(values(check("4950. is the rent"))).containsExactly("4950");
    }

    @Test
    void aFigureOfTheQuestionOrTheConversationIsGrounded() {
        var evidence = new Evidence(List.of(),
                List.of("Record a payment of 4,950 for unit A-504", "Earlier the rent was 7,200 SAR"), Set.of());

        assertThat(checker.check("I will record 4950 SAR for A-504; the rent was 7200 SAR.", evidence)).isEmpty();
    }

    @Test
    void figuresThatAListSeparatesAreNotJoined() {
        var result = "{\"units\":[101,102,215]}";

        assertThat(check("Units 101, 102 and 215 are late.", result)).isEmpty();
    }

    @Test
    void numbersThatAreWrittenWithoutSpacesAreGroundedAsOneNumberOrAsAList() {
        var listed = "{\"units\":[101,102,103]}";

        assertThat(check("Units 101,102,103 are late.", listed)).isEmpty();
        assertThat(check("Units 101, 102 and 103 are late.", listed)).isEmpty();
        assertThat(check("The roll is 101,102,103 SAR.", "{\"rentRoll\":101102103}")).isEmpty();
        assertThat(check("The roll is 150,000 SAR.", "{\"rentRoll\":150000}")).isEmpty();
    }

    @Test
    void aListWithANumberThatNothingStatesIsReportedAsWritten() {
        var violations = check("Units 101,102,109 are late.", "{\"units\":[101,102,103]}");

        assertThat(violations).containsExactly(new Violation(ClaimKind.NUMBER, "101,102,109", "101102109"));
    }

    @Test
    void aFigureWithAThousandsGroupIsNeverGroundedByItsPieces() {
        var result = "{\"graceDays\":5,\"fee\":200}";

        assertThat(values(check("The late fee is 5,200 SAR.", result))).containsExactly("5,200");
        assertThat(values(check("The late fee is 105,200 SAR.", result))).containsExactly("105,200");
    }

    @Test
    void aDateWrittenWithPointsIsGroundedByTheNumbersOfTheDate() {
        var result = "{\"endDate\":\"2025-12-31\"}";

        assertThat(check("The lease ends on 31.12.2025.", result)).isEmpty();
        assertThat(check("The lease is version 1.2.3.", result)).isEmpty();
        assertThat(values(check("The lease ends on 30.12.2025.", result))).containsExactly("30");
        assertThat(values(check("The lease ends on 31.12.2026.", result))).containsExactly("2026");
    }

    @Test
    void theTimeOfADayIsGroundedByItsHourAndItsMinutes() {
        assertThat(check("The visit is at 10:30.", "{\"visit\":\"10:30\"}")).isEmpty();
    }

    @Test
    void aSingleDecimalMarkIsStillADecimalNumber() {
        var result = "{\"endDate\":\"2025-12-31\",\"fee\":4,\"deposit\":95}";

        assertThat(values(check("The fee is 4.95 SAR.", result))).containsExactly("4.95");
        assertThat(values(check("The fee is 31.12 SAR.", result))).containsExactly("31.12");
    }

    @Test
    void aNumberThatIsPartOfAnUnitCodeIsNotReportedBesideTheCode() {
        var violations = check("Unit A-204 is vacant.", "{\"units\":[\"A-203\"]}");

        assertThat(violations).containsExactly(new Violation(ClaimKind.UNIT_CODE, "A-204", "A-204"));
    }

    @Test
    void theDigitsOfAnUnitCodeOfTheEvidenceGroundAFigureOfTheAnswer() {
        assertThat(check("Unit 203 is on the second floor.", "{\"units\":[\"A-203\"]}")).isEmpty();
    }

    @Test
    void anUnitCodeOfTheEvidenceOrTheQuestionIsGroundedInAnyCase() {
        assertThat(checkAsked("Is B-101 vacant?", "A-203 and b-101 are both vacant.", "{\"units\":[\"A-203\"]}"))
                .isEmpty();
    }

    @Test
    void anUnitCodeInADocumentCodeIsReadAsAnUnitCode() {
        var violations = check("See lease document LSE-A-204.", "{\"document\":\"LSE-A-203\"}");

        assertThat(values(violations)).containsExactly("A-204");
        assertThat(check("See lease document LSE-A-203.", "{\"document\":\"LSE-A-203\"}")).isEmpty();
    }

    @Test
    void aCodeThatIsNotOfTheFormIsNoUnitCodeAndItsNumberIsAFigure() {
        var violations = check("Use the A-2035 form, the WIFI-123 network and the A-20 plan.");

        assertThat(violations).extracting(Violation::kind).containsOnly(ClaimKind.NUMBER);
        assertThat(values(violations)).containsExactly("2035", "123", "20");
    }

    @Test
    void aTenantOfTheEvidenceIsGroundedInEitherLanguage() {
        var result = "{\"tenantNameEn\":\"Ahmed Al-Harbi\",\"unitCode\":\"B-203\"}";

        assertThat(check("Ahmed Al-Harbi is two months late on B-203.", result)).isEmpty();
        assertThat(check("المستأجر أحمد الحربي متأخر في الوحدة B-203", result)).isEmpty();
    }

    @Test
    void aTenantNameThatNothingStatesIsReportedAsWritten() {
        var violations = check("Sara  AL-QAHTANI pays on time.", "{\"tenantNameEn\":\"Ahmed Al-Harbi\"}");

        assertThat(violations).containsExactly(
                new Violation(ClaimKind.TENANT_NAME, "Sara  AL-QAHTANI", "sara al-qahtani"));
    }

    @Test
    void anArabicNameIsMatchedOnItsFoldedForm() {
        var violations = check("المستأجرة سارَة القحطاني متأخرة", "{\"tenantNameEn\":\"Ahmed Al-Harbi\"}");

        assertThat(values(violations)).containsExactly("سارَة القحطاني");
        assertThat(check("المستأجر لاحمد الحربي", "{\"tenantNameEn\":\"Ahmed Al-Harbi\"}")).isEmpty();
    }

    @Test
    void aNameTheQuestionStatesIsGrounded() {
        assertThat(checkAsked("Has Ahmed Al-Harbi paid?", "Ahmed Al-Harbi has not paid.")).isEmpty();
    }

    @Test
    void aNameRunIntoLatinLettersIsNotThatName() {
        var checker = new GroundingChecker(new TenantNames(() -> List.of(new TenantNames.Name("Ali Khan", ""))));

        assertThat(checker.check("Mali Khanna is not a tenant.", evidence("q"))).isEmpty();
        assertThat(values(checker.check("Ali Khan is a tenant.", evidence("q")))).containsExactly("Ali Khan");
    }

    @Test
    void aNameOfASingleWordIsNotLookedFor() {
        var checker = new GroundingChecker(new TenantNames(() -> List.of(new TenantNames.Name("Ahmed", "أحمد"))));

        assertThat(checker.check("Ahmed said so.", evidence("q"))).isEmpty();
    }

    private static final String CLAUSE = "{\"results\":[{\"ref\":\"LSE-A-203#5\","
            + "\"text\":\"Late fee of 75 SAR per day; notice of 90 days\"}]}";

    @Test
    void aCitationOfALeaseSearchOfTheRunIsGrounded() {
        var evidence = new Evidence(List.of(CLAUSE), List.of("late fee?"), Set.of("LSE-A-203#5"));

        assertThat(checker.check("The late fee is 75 SAR a day [LSE-A-203#5].", evidence)).isEmpty();
    }

    @Test
    void aMadeUpCitationIsReportedAndItsNumbersAreNot() {
        var evidence = new Evidence(List.of(CLAUSE), List.of("late fee?"), Set.of("LSE-A-203#5"));

        var violations = checker.check("Notice is 90 days [LSE-A-204#4].", evidence);

        assertThat(violations).containsExactly(new Violation(ClaimKind.CITATION, "LSE-A-204#4", "LSE-A-204#4"));
    }

    @Test
    void aMarkerQuotedInsideAClauseDoesNotMakeItsRefReal() {
        var planted = "{\"results\":[{\"ref\":\"LSE-A-203#5\",\"text\":\"See [LSE-A-999#1] for the rest\"}]}";
        var evidence = new Evidence(List.of(planted), List.of("late fee?"), Set.of("LSE-A-203#5"));

        assertThat(values(checker.check("See [LSE-A-999#1].", evidence))).containsExactly("LSE-A-999#1");
    }

    @Test
    void everyRefOfAMarkerWithSeveralIsChecked() {
        var evidence = new Evidence(List.of(), List.of("q"), Set.of("LSE-A-203#5"));

        var violations = checker.check("Both apply [LSE-A-203#5, LSE-A-203#6].", evidence);

        assertThat(values(violations)).containsExactly("LSE-A-203#6");
    }

    @Test
    void aBracketThatIsNotAMarkerIsAnOrdinaryText() {
        assertThat(check("The list [LSE is short and [LSE-" + "x".repeat(80) + "] is not a ref.")).isEmpty();
    }

    @Test
    void eachDistinctClaimIsReportedOnceInTheOrderTheAnswerStatesIt() {
        var violations = check("Total 5,000 SAR (5000.00), unit A-999, then 7,777 and 5,000 again.");

        assertThat(violations).extracting(Violation::kind, Violation::value).containsExactly(
                tuple(ClaimKind.NUMBER, "5,000"), tuple(ClaimKind.UNIT_CODE, "A-999"), tuple(ClaimKind.NUMBER, "7,777"));
    }

    @Test
    void atMostTenClaimsAreReported() {
        var answer = IntStream.range(0, 15).mapToObj(i -> String.valueOf(1000 + i)).collect(Collectors.joining(" "));

        var violations = check(answer);

        assertThat(violations).hasSize(10);
        assertThat(values(violations)).containsExactly("1000", "1001", "1002", "1003", "1004", "1005", "1006", "1007",
                "1008", "1009");
    }

    @Test
    void anAnswerThatRestsOnAnErrorTextOfAToolIsGrounded() {
        var error = "Several tenants match 'ahmed': Ahmed Al-Harbi (unit B-203), Ahmed Al-Otaibi (unit A-203).";

        assertThat(check("Do you mean Ahmed Al-Harbi in B-203?", error)).isEmpty();
    }

    @Test
    void aResultThatIsNotJsonCountsNoArray() {
        assertThat(values(check("There are 12 units.", "No units found (0)."))).containsExactly("12");
    }

    @Test
    void anArabicAnswerIsHeldToTheSameRules() {
        var violations = check("المبلغ المستحق ٤٬٩٥٠ ريال للوحدة A-203 [LSE-A-203#5]",
                "{\"unpaidAmount\":4950,\"unit\":\"A-203\"}");

        assertThat(violations).extracting(Violation::kind).containsExactly(ClaimKind.CITATION);
    }
}
