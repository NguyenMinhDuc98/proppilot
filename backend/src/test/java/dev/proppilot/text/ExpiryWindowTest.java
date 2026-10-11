package dev.proppilot.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpiryWindowTest {

    private static OptionalInt days(String question) {
        return ExpiryWindow.days(Folding.fold(Folding.withoutDiacritics(question)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "which leases end in the next 60 days|60",
            "leases ending within two months|60",
            "what expires in 3 weeks|21",
            "which leases end this month|30",
            "ما العقود التي تنتهي خلال 3 أشهر|90",
            "ما العقود التي تنتهي خلال ٦٠ يومًا|60",
            "ما العقود التي تنتهي خلال شهرين|60",
            "which leases end in the next 999999999 days|365"})
    void readsHowFarAheadAQuestionLooks(String question, int expected) {
        assertThat(days(question)).hasValue(expected);
    }

    @Test
    void aNumberThatIsNotLedInByAWordLikeWithinIsNotAWindow() {
        assertThat(days("which leases require 90 days notice")).isEmpty();
    }

    @Test
    void aDigitOutsideTheBasicMultilingualPlaneIsNotANumberAndDoesNotFail() {
        var mathematicalFive = new String(Character.toChars(0x1D7D3));
        var adlamOne = new String(Character.toChars(0x1E951));

        assertThat(days("which leases end within " + mathematicalFive + " days")).isEmpty();
        assertThat(days("ما العقود التي تنتهي خلال " + adlamOne + " يوما")).isEmpty();
    }

    @Test
    void noDecimalDigitOfTheSupplementaryPlanesFailsTheRead() {
        for (int codePoint = Character.MIN_SUPPLEMENTARY_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.getType(codePoint) != Character.DECIMAL_DIGIT_NUMBER) {
                continue;
            }
            var digit = new String(Character.toChars(codePoint));

            assertThat(days("leases that end within " + digit + digit + " days")).isEmpty();
            assertThat(days("العقود التي تنتهي خلال " + digit + " اشهر")).isEmpty();
        }
    }
}
