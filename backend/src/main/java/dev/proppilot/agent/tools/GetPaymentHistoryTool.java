package dev.proppilot.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class GetPaymentHistoryTool implements Tool {

    private final TenantResolver resolver;
    private final PaymentRepository payments;
    private final Clock clock;
    private final ObjectMapper json;

    GetPaymentHistoryTool(TenantResolver resolver, PaymentRepository payments, Clock clock, ObjectMapper json) {
        this.resolver = resolver;
        this.payments = payments;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String name() {
        return "get_payment_history";
    }

    @Override
    public String description() {
        return "Monthly rent payment history (newest first) for the current lease of one tenant or unit, with paid / late "
                + "/ unpaid status. Set all_leases to also get the unit's previous leases, which come back separately "
                + "under earlierLeases with their own tenant: they are not the current tenant's payments.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("unit_code", "Unit code such as A-203")
                .string("tenant_name", "Tenant name in English or Arabic (partial match)")
                .integer("months", "How many recent months to return (default 12, max 12)")
                .bool("all_leases", "Also return the payments of the unit's previous leases (default false: current lease only)")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var args = new ToolArgs(input);
        int months = args.integer("months", 1, 12).orElse(12);
        boolean allLeases = args.bool("all_leases").orElse(false);
        var resolution = resolver.resolve(args.string("tenant_name"), args.string("unit_code"));
        if (resolution instanceof TenantResolver.Resolution.Failed failed) {
            return ToolResult.error(failed.reason());
        }
        var lease = ((TenantResolver.Resolution.Found) resolution).lease();
        var today = LocalDate.now(clock);

        var page = PageRequest.of(0, months);
        List<Payment> history = allLeases
                ? payments.findByUnitCode(lease.getUnit().getCode(), page)
                : payments.findByLeaseIdOrderByPeriodDesc(lease.getId(), page);
        var currentAndEarlier = history.stream().collect(Collectors.partitioningBy(p -> p.getLease().getId().equals(lease.getId())));

        var rows = toRows(currentAndEarlier.get(true), today);
        var earlierLeases = allLeases ? earlierLeases(currentAndEarlier.get(false), today) : null;
        var data = new Result(lease.getTenant().getNameEn(), lease.getTenant().getNameAr(), lease.getUnit().getCode(),
                allLeases, unpaidMonths(rows), rows, earlierLeases);
        long unpaidShown = history.stream().filter(p -> !p.isPaid()).count();
        return ToolResult.ok(json, data, history.size() + " months for " + data.unitCode() + ", " + unpaidShown + " unpaid");
    }

    private static List<EarlierLease> earlierLeases(List<Payment> earlier, LocalDate today) {
        return earlier.stream()
                .collect(Collectors.groupingBy(p -> p.getLease().getId(), LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(leasePayments -> {
                    var lease = leasePayments.get(0).getLease();
                    var rows = toRows(leasePayments, today);
                    return new EarlierLease(lease.getTenant().getNameEn(), lease.getTenant().getNameAr(),
                            lease.getStartDate(), lease.getEndDate(), unpaidMonths(rows), rows);
                })
                .toList();
    }

    private static List<Row> toRows(List<Payment> payments, LocalDate today) {
        return payments.stream().map(p -> toRow(p, today)).toList();
    }

    private static long unpaidMonths(List<Row> rows) {
        return rows.stream().filter(r -> r.status().equals("UNPAID")).count();
    }

    private static Row toRow(Payment p, LocalDate today) {
        String status;
        Long daysLate = null;
        if (p.isPaid()) {
            long late = ChronoUnit.DAYS.between(p.getDueDate(), p.getPaidOn());
            status = late > 5 ? "PAID_LATE" : "PAID_ON_TIME";
            daysLate = Math.max(late, 0);
        } else {
            status = "UNPAID";
            daysLate = Math.max(ChronoUnit.DAYS.between(p.getDueDate(), today), 0);
        }
        return new Row(p.getPeriod().toString().substring(0, 7), p.getAmount(), p.getPaidAmount(), p.getPaidOn(), status, daysLate);
    }

    /** The tenant, payments and unpaid months at the top level are those of the current lease; earlier leases stay apart. */
    record Result(String tenantEn, String tenantAr, String unitCode, boolean allLeases, long unpaidMonths,
                  List<Row> payments, @JsonInclude(JsonInclude.Include.NON_NULL) List<EarlierLease> earlierLeases) {
    }

    record EarlierLease(String tenantEn, String tenantAr, LocalDate startDate, LocalDate endDate, long unpaidMonths,
                        List<Row> payments) {
    }

    record Row(String month, BigDecimal amount, BigDecimal paidAmount, LocalDate paidOn, String status, Long daysLate) {
    }
}
