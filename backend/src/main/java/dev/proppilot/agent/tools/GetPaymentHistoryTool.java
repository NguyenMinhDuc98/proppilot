package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.Payment;
import dev.proppilot.domain.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
                + "/ unpaid status. Set all_leases to also include the unit's previous leases.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("unit_code", "Unit code such as A-203")
                .string("tenant_name", "Tenant name in English or Arabic (partial match)")
                .integer("months", "How many recent months to return (default 12, max 12)")
                .bool("all_leases", "Also include payments of the unit's previous leases (default false: current lease only)")
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
        var rows = history.stream().map(p -> toRow(p, today)).toList();
        long unpaid = rows.stream().filter(r -> r.status().equals("UNPAID")).count();
        var data = new Result(lease.getTenant().getNameEn(), lease.getTenant().getNameAr(), lease.getUnit().getCode(),
                allLeases, unpaid, rows);
        return ToolResult.ok(json, data, rows.size() + " months for " + data.unitCode() + ", " + unpaid + " unpaid");
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

    record Result(String tenantEn, String tenantAr, String unitCode, boolean allLeases, long unpaidMonths,
                  List<Row> payments) {
    }

    record Row(String month, BigDecimal amount, BigDecimal paidAmount, LocalDate paidOn, String status, Long daysLate) {
    }
}
