package dev.proppilot.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TraceTextTest {

    @Test
    void leavesTextWithinTheCapAsItWas() {
        assertThat(TraceText.capped("{\"unit\":\"A-203\"}", 100)).isEqualTo("{\"unit\":\"A-203\"}");
        assertThat(TraceText.capped("12345", 5)).isEqualTo("12345");
    }

    @Test
    void cutsLongerTextSoThatItEndsWithAnEllipsisAndStillFits() {
        var capped = TraceText.capped("0123456789", 6);

        assertThat(capped).isEqualTo("01234…").hasSize(6);
    }

    @Test
    void neverSplitsASurrogatePair() {
        var emoji = "🏠";

        var capped = TraceText.capped("ab" + emoji + emoji, 4);

        assertThat(capped).isEqualTo("ab…");
        assertThat(capped.chars().noneMatch(unit -> Character.isSurrogate((char) unit))).isTrue();
    }

    @Test
    void turnsControlCharactersIntoSpacesSoTheTextCanBeStored() {
        assertThat(TraceText.capped("a\u0000b\nc\td", 100)).isEqualTo("a b c d");
    }

    @Test
    void treatsNullAsEmpty() {
        assertThat(TraceText.capped(null, 10)).isEmpty();
    }

    @Test
    void capsAHugeInputQuicklyWithoutRecursion() {
        var hostile = "(a|b)".repeat(2_000_000);

        var started = System.nanoTime();
        var capped = TraceText.capped(hostile, 2000);

        assertThat(capped).hasSize(2000);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
