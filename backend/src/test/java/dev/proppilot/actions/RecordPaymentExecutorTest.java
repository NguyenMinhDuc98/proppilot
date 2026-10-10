package dev.proppilot.actions;

import static dev.proppilot.actions.Fixtures.invoice;
import static dev.proppilot.actions.Fixtures.lease;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.PaymentMethod;
import dev.proppilot.domain.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecordPaymentExecutorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate LEASE_START = LocalDate.of(2025, 11, 1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LeaseTargets targets = mock(LeaseTargets.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T09:00:00Z"), ZoneOffset.UTC);
    private final RecordPaymentExecutor executor = new RecordPaymentExecutor(targets, payments, mock(jakarta.persistence.EntityManager.class), clock, JSON);

    @BeforeEach
    void leaseOwingThreeThousand() {
        var lease = lease("A-203", LEASE_START);
        when(targets.resolve(any())).thenReturn(lease);
        List<Payment> owed = List.of(invoice(1, LocalDate.of(2026, 8, 1), "1000", "0"),
                invoice(2, LocalDate.of(2026, 9, 1), "1000", "0"), invoice(3, LocalDate.of(2026, 10, 1), "1000", "0"));
        when(payments.findOutstanding(eq(99L), eq(TODAY))).thenReturn(owed);
    }

    private ObjectNode args() {
        return JSON.createObjectNode().put("unit_code", "A-203").put("amount", 1000).put("method", "CASH");
    }

    private ActionError refusedWith(ObjectNode args) {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(ActionException.class, () -> executor.validate(args));
        return thrown.error();
    }

    @Test
    void normalizesAValidPayment() {
        var validated = executor.validate(args().put("amount", 1000.5).put("payment_date", "2026-10-01").put("method", "cheque"));

        assertThat(validated.amount()).isEqualTo(new BigDecimal("1000.50"));
        assertThat(validated.paymentDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(validated.method()).isEqualTo(PaymentMethod.CHEQUE);
        assertThat(validated.overridePayment()).isFalse();
        assertThat(executor.arguments(validated).toString())
                .contains("\"unit_code\":\"A-203\"").contains("\"payment_date\":\"2026-10-01\"").contains("\"method\":\"CHEQUE\"");
    }

    @Test
    void theDateDefaultsToToday() {
        assertThat(executor.validate(args()).paymentDate()).isEqualTo(TODAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "999.99", "3000", "1E+3", "2000.10"})
    void acceptsAmountsWithAtMostTwoDecimalsWithinTheBalance(String amount) {
        var validated = executor.validate(args().put("amount", new BigDecimal(amount)));

        assertThat(validated.amount().scale()).isEqualTo(2);
        assertThat(validated.amount()).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.001", "10.005", "1000.999", "10000000.01"})
    void rejectsAZeroAmountTooManyDecimalsOrAHugeAmount(String amount) {
        assertThat(refusedWith(args().put("amount", new BigDecimal(amount)))).isEqualTo(ActionError.INVALID_AMOUNT);
    }

    @Test
    void rejectsNegativeMissingAndNonNumericAmounts() {
        assertThatThrownBy(() -> executor.validate(args().put("amount", -5))).isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> executor.validate(args().put("amount", "lots"))).isInstanceOf(ToolInputException.class)
                .hasMessage("amount must be a number");
        assertThatThrownBy(() -> executor.validate(args().without("amount"))).isInstanceOf(ToolInputException.class)
                .hasMessage("amount is required");
    }

    @Test
    void rejectsADateInTheFutureAndOneBeforeTheLeaseStarted() {
        assertThat(refusedWith(args().put("payment_date", "2026-10-11"))).isEqualTo(ActionError.PAYMENT_DATE_IN_FUTURE);
        assertThat(refusedWith(args().put("payment_date", "2025-10-31"))).isEqualTo(ActionError.PAYMENT_BEFORE_LEASE_START);
        assertThat(executor.validate(args().put("payment_date", "2026-10-10")).paymentDate()).isEqualTo(TODAY);
        assertThat(executor.validate(args().put("payment_date", "2025-11-01")).paymentDate()).isEqualTo(LEASE_START);
    }

    @Test
    void rejectsAMalformedDate() {
        assertThatThrownBy(() -> executor.validate(args().put("payment_date", "10/10/2026")))
                .isInstanceOf(ToolInputException.class).hasMessageStartingWith("payment_date must be");
    }

    @Test
    void requiresAKnownPaymentMethod() {
        assertThatThrownBy(() -> executor.validate(args().without("method")))
                .isInstanceOf(ToolInputException.class).hasMessage("method is required");
        assertThatThrownBy(() -> executor.validate(args().put("method", "BITCOIN")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("method must be one of");
    }

    @Test
    void refusesMoreThanIsOutstandingUnlessOverridden() {
        assertThat(refusedWith(args().put("amount", new BigDecimal("3000.01")))).isEqualTo(ActionError.AMOUNT_EXCEEDS_OUTSTANDING);

        var overridden = executor.validate(args().put("amount", new BigDecimal("3000.01")).put("overridePayment", true));

        assertThat(overridden.overridePayment()).isTrue();
    }

    @Test
    void theOverrideMustBeABoolean() {
        assertThatThrownBy(() -> executor.validate(args().put("overridePayment", "yes")))
                .isInstanceOf(ToolInputException.class).hasMessage("overridePayment must be a boolean");
    }

    @Test
    void refusesWithItsOwnCodeWhenNothingIsOutstanding() {
        when(payments.findOutstanding(eq(99L), any())).thenReturn(List.of());

        assertThat(refusedWith(args())).isEqualTo(ActionError.NOTHING_OUTSTANDING);
        assertThat(executor.validate(args().put("overridePayment", true)).amount()).isEqualByComparingTo("1000");
    }

    @Test
    void onlyTheAmountAndTheOverrideCanBeEditedAtApproval() {
        assertThat(executor.editableFields()).containsExactlyInAnyOrder("amount", "overridePayment");
    }

    @Test
    void summarizesTheChangeInBothLanguages() {
        var summary = executor.summary(executor.validate(args().put("amount", 1234.5).put("method", "BANK_TRANSFER")));

        assertThat(summary.en()).isEqualTo(
                "Record a SAR 1,234.50 bank transfer payment from Ahmed Al-Harbi for unit A-203, received on 2026-10-10.");
        assertThat(summary.ar()).isEqualTo(
                "تسجيل دفعة بقيمة 1,234.50 ريال سعودي من أحمد الحربي للوحدة A-203، بتاريخ 2026-10-10، طريقة الدفع: تحويل بنكي.");
    }
}
