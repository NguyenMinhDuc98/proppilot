package dev.proppilot.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TenantNamesTest {

    private final AtomicInteger reads = new AtomicInteger();
    private final List<TenantNames.Name> table = new ArrayList<>(List.of(new TenantNames.Name("Ahmed Al-Harbi", "")));
    private final TenantNames names = new TenantNames(() -> {
        reads.incrementAndGet();
        return List.copyOf(table);
    });
    private final GroundingChecker checker = new GroundingChecker(names);

    private boolean flags(String answer) {
        return !checker.check(answer, new Evidence(List.of(), List.of("q"), Set.of())).isEmpty();
    }

    @Test
    void theNamesAreReadOnceWhenTheFirstAnswerIsCheckedAndNotAgain() {
        assertThat(reads).hasValue(0);

        flags("Ahmed Al-Harbi");
        flags("Ahmed Al-Harbi again");

        assertThat(reads).hasValue(1);
    }

    @Test
    void aChangeToTheTableIsSeenOnlyAfterARefresh() {
        assertThat(flags("Zaid Al-Newcomer pays.")).isFalse();
        table.add(new TenantNames.Name("Zaid Al-Newcomer", "زيد القادم"));
        assertThat(flags("Zaid Al-Newcomer pays.")).isFalse();

        names.refresh();

        assertThat(flags("Zaid Al-Newcomer pays.")).isTrue();
        assertThat(flags("زيد القادم يدفع")).isTrue();
        assertThat(reads).hasValue(2);
    }

    @Test
    void aTenantWithoutAnArabicNameOrWithNullNamesIsKept() {
        table.add(new TenantNames.Name(null, "سارة القحطاني"));
        table.add(new TenantNames.Name("Sara Al-Qahtani", null));

        assertThat(flags("سارة القحطاني")).isTrue();
        assertThat(flags("Sara Al-Qahtani")).isTrue();
    }
}
