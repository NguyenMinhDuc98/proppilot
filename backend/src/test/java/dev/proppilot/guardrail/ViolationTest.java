package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ViolationTest {

    @Test
    void aValueIsCutToFortyCharactersAndFreeOfControlCharacters() {
        var violation = new Violation(ClaimKind.CITATION, "LSE-" + "A".repeat(60), "x\u0000y\nz");

        assertThat(violation.value()).hasSize(Violation.MAX_LENGTH).endsWith("…");
        assertThat(violation.normalised()).isEqualTo("x y z");
    }

    @Test
    void aShortValueIsKeptAsItIs() {
        assertThat(new Violation(ClaimKind.NUMBER, "4,950", "4950").value()).isEqualTo("4,950");
    }

    @Test
    void describesTheViolationsByKindAndValueOnly() {
        var described = Violation.describe(List.of(new Violation(ClaimKind.NUMBER, "4,950", "4950"),
                new Violation(ClaimKind.UNIT_CODE, "A-204", "A-204")));

        assertThat(described).isEqualTo("NUMBER \"4,950\", UNIT_CODE \"A-204\"");
    }
}
