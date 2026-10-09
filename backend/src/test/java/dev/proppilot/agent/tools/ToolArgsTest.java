package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToolArgsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolArgs args(String json) throws Exception {
        return new ToolArgs(JSON.readTree(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "5.0", "5.00", "0.5E1", "50E-1"})
    void integerAcceptsIntegralNumbersIncludingWholeDecimals(String number) throws Exception {
        assertThat(args("{\"months\":" + number + "}").integer("months", 1, 12)).contains(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.7", "2.5", "2.0000001", "0.4", "-2.7", "1e-3"})
    void integerRejectsEveryNonIntegralNumber(String number) {
        assertThatThrownBy(() -> args("{\"months\":" + number + "}").integer("months", -12, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be an integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5\"", "\"two\"", "true", "[3]", "{\"n\":3}"})
    void integerRejectsEverythingThatIsNotANumber(String value) {
        assertThatThrownBy(() -> args("{\"months\":" + value + "}").integer("months", 1, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be an integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3000000000", "-3000000000", "1e30", "99999999999999999999"})
    void integerRejectsNumbersTooBigForAnInt(String number) {
        assertThatThrownBy(() -> args("{\"months\":" + number + "}").integer("months", 1, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be an integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "13", "-1", "13.0"})
    void integerRejectsValuesOutsideTheRange(String number) {
        assertThatThrownBy(() -> args("{\"months\":" + number + "}").integer("months", 1, 12))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("months must be between 1 and 12");
    }

    @Test
    void integerIsEmptyWhenMissingOrNull() throws Exception {
        assertThat(args("{}").integer("months", 1, 12)).isEmpty();
        assertThat(args("{\"months\":null}").integer("months", 1, 12)).isEmpty();
        assertThat(new ToolArgs(null).integer("months", 1, 12)).isEmpty();
    }

    @Test
    void boolReadsTrueAndFalse() throws Exception {
        assertThat(args("{\"all\":true}").bool("all")).contains(true);
        assertThat(args("{\"all\":false}").bool("all")).contains(false);
    }

    @Test
    void boolIsEmptyWhenMissingOrNull() throws Exception {
        assertThat(args("{}").bool("all")).isEmpty();
        assertThat(args("{\"all\":null}").bool("all")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "\"false\"", "1", "0", "1.0", "[true]", "{}"})
    void boolRejectsValuesThatAreNotBooleans(String value) {
        assertThatThrownBy(() -> args("{\"all\":" + value + "}").bool("all"))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("all must be a boolean");
    }
}
