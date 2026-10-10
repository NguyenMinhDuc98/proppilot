package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.agent.tools.ToolArgs;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.domain.Lease;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.PaymentMethod;
import dev.proppilot.domain.PaymentRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Records money received from a tenant against the lease's due invoices, oldest first. Invoices may be paid in part.
 *
 * <p>Paying more than is outstanding is refused unless {@code overridePayment} is set. Even then the excess is not
 * written to any invoice, because an invoice never holds more than its amount and there is no credit ledger yet: it is
 * only reported as {@code overpaymentCredit} in the result and in the audit log.
 */
@Component
class RecordPaymentExecutor implements ActionExecutor<RecordPaymentExecutor.PaymentArgs> {

    static final String AMOUNT = "amount";
    static final String OVERRIDE_PAYMENT = "overridePayment";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    record PaymentArgs(Lease lease, BigDecimal amount, LocalDate paymentDate, PaymentMethod method,
                       boolean overridePayment) {

        String unitCode() {
            return lease.getUnit().getCode();
        }
    }

    private final LeaseTargets targets;
    private final PaymentRepository payments;
    private final EntityManager entityManager;
    private final Clock clock;
    private final ObjectMapper json;

    RecordPaymentExecutor(LeaseTargets targets, PaymentRepository payments, EntityManager entityManager, Clock clock,
                          ObjectMapper json) {
        this.targets = targets;
        this.payments = payments;
        this.entityManager = entityManager;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public ActionKind kind() {
        return ActionKind.RECORD_PAYMENT;
    }

    @Override
    public Set<String> editableFields() {
        return Set.of(AMOUNT, OVERRIDE_PAYMENT);
    }

    @Override
    public PaymentArgs validate(JsonNode rawArgs) {
        var in = new ToolArgs(rawArgs);
        var lease = targets.resolve(in);
        var amount = amount(in);
        var today = LocalDate.now(clock);
        var date = in.date("payment_date").orElse(today);
        if (date.isAfter(today)) {
            throw new ActionException(ActionError.PAYMENT_DATE_IN_FUTURE, "payment_date must not be in the future.");
        }
        if (date.isBefore(lease.getStartDate())) {
            throw new ActionException(ActionError.PAYMENT_BEFORE_LEASE_START,
                    "payment_date " + date + " is before the lease started on " + lease.getStartDate() + ".");
        }
        var method = in.enumValue("method", PaymentMethod.class)
                .orElseThrow(() -> new ToolInputException("method is required"));
        var args = new PaymentArgs(lease, amount, date, method, in.bool(OVERRIDE_PAYMENT).orElse(false));
        requireWithinBalance(args, Payment.totalRemaining(outstanding(args)));
        return args;
    }

    private static BigDecimal amount(ToolArgs in) {
        var amount = in.decimal(AMOUNT).orElseThrow(() -> new ToolInputException("amount is required"));
        if (amount.signum() == 0) {
            throw new ActionException(ActionError.INVALID_AMOUNT, "amount must be greater than zero.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ActionException(ActionError.INVALID_AMOUNT, "amount must be at most " + MAX_AMOUNT.toPlainString() + ".");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new ActionException(ActionError.INVALID_AMOUNT, "amount must have at most 2 decimal places.");
        }
        return amount.setScale(2);
    }

    private void requireWithinBalance(PaymentArgs args, BigDecimal outstanding) {
        if (args.overridePayment() || args.amount().compareTo(outstanding) <= 0) {
            return;
        }
        if (outstanding.signum() == 0) {
            throw new ActionException(ActionError.NOTHING_OUTSTANDING,
                    "Unit " + args.unitCode() + " has no rent outstanding, so there is nothing to record a payment against.");
        }
        throw new ActionException(ActionError.AMOUNT_EXCEEDS_OUTSTANDING, "The amount SAR " + Money.format(args.amount())
                + " is more than the SAR " + Money.format(outstanding) + " outstanding for unit " + args.unitCode() + ".");
    }

    private List<Payment> outstanding(PaymentArgs args) {
        return payments.findOutstanding(args.lease().getId(), LocalDate.now(clock));
    }

    @Override
    public JsonNode arguments(PaymentArgs args) {
        var node = json.createObjectNode();
        node.put("unit_code", args.unitCode());
        node.put(AMOUNT, args.amount());
        node.put("payment_date", args.paymentDate().toString());
        node.put("method", args.method().name());
        node.put(OVERRIDE_PAYMENT, args.overridePayment());
        return node;
    }

    @Override
    public Summary summary(PaymentArgs args) {
        var tenant = args.lease().getTenant();
        var amount = Money.format(args.amount());
        return new Summary(
                "Record a SAR " + amount + " " + ActionLabels.method(args.method(), false) + " payment from "
                        + tenant.getNameEn() + " for unit " + args.unitCode() + ", received on " + args.paymentDate() + ".",
                "تسجيل دفعة بقيمة " + amount + " ريال سعودي من " + tenant.getNameAr() + " للوحدة " + args.unitCode()
                        + "، بتاريخ " + args.paymentDate() + "، طريقة الدفع: " + ActionLabels.method(args.method(), true) + ".");
    }

    @Override
    public JsonNode preview(PaymentArgs args) {
        var rows = outstanding(args);
        var allocation = PaymentAllocator.allocate(rows, args.amount());
        var outstandingBefore = Payment.totalRemaining(rows);

        var node = json.createObjectNode();
        describeTarget(node, args);
        node.put("outstandingBefore", outstandingBefore);
        node.put("outstandingAfter", outstandingBefore.subtract(allocation.applied()));
        node.put("overpaymentCredit", allocation.excess());
        var changes = node.putArray("payments");
        for (var slice : allocation.slices()) {
            var row = changes.addObject();
            row.put("paymentId", slice.payment().getId());
            row.put("period", slice.payment().getPeriod().toString());
            row.put("amount", slice.payment().getAmount());
            row.put("paidBefore", slice.paidBefore());
            row.put("applied", slice.applied());
            row.put("paidAfter", slice.paidAfter());
            row.put("remainingAfter", slice.payment().getAmount().subtract(slice.paidAfter()));
            row.put("status", slice.settles() ? "PAID" : "PARTIAL");
        }
        return node;
    }

    @Override
    public Execution execute(PaymentArgs args, ExecutionContext context) {
        // Lock first and look at the balance afterwards: another approval may have paid these invoices since validate.
        // Validation already loaded them into this session, and a locking query hands those instances back as they
        // were read, so they are read again once the lock is held.
        var rows = payments.lockOutstanding(args.lease().getId(), LocalDate.now(clock));
        rows.forEach(entityManager::refresh);
        var outstandingBefore = Payment.totalRemaining(rows);
        requireWithinBalance(args, outstandingBefore);

        var allocation = PaymentAllocator.allocate(rows, args.amount());
        var before = json.createArrayNode();
        allocation.slices().forEach(slice -> before.add(invoiceState(slice.payment())));
        allocation.slices().forEach(slice ->
                slice.payment().receive(slice.applied(), args.paymentDate(), args.method(), context.actionId()));
        var after = json.createArrayNode();
        allocation.slices().forEach(slice -> after.add(invoiceState(slice.payment())));

        var result = json.createObjectNode();
        describeTarget(result, args);
        result.put("applied", allocation.applied());
        result.put("overpaymentCredit", allocation.excess());
        result.put("outstandingAfter", outstandingBefore.subtract(allocation.applied()));
        result.set("payments", after);
        return new Execution(result, "LEASE_PAYMENTS", String.valueOf(args.lease().getId()), before, after);
    }

    private void describeTarget(ObjectNode node, PaymentArgs args) {
        node.put("unitCode", args.unitCode());
        node.put("tenantEn", args.lease().getTenant().getNameEn());
        node.put("tenantAr", args.lease().getTenant().getNameAr());
        node.put(AMOUNT, args.amount());
        node.put("paymentDate", args.paymentDate().toString());
        node.put("method", args.method().name());
    }

    private ObjectNode invoiceState(Payment payment) {
        var node = json.createObjectNode();
        node.put("paymentId", payment.getId());
        node.put("period", payment.getPeriod().toString());
        node.put("amount", payment.getAmount());
        node.put("paidAmount", payment.getPaidAmount());
        node.put("paidOn", payment.getPaidOn() == null ? null : payment.getPaidOn().toString());
        node.put("method", payment.getMethod() == null ? null : payment.getMethod().name());
        return node;
    }
}
